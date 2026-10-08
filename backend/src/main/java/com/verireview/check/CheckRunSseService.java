package com.verireview.check;

import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.security.VeriReviewUserDetails;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Service for Server-Sent Events streaming of check run updates.
 * Supports per-project check stream and dashboard summary stream.
 * Includes heartbeat and automatic reconnect handling.
 */
@Service
public class CheckRunSseService {

  private static final Logger log = LoggerFactory.getLogger(CheckRunSseService.class);
  private static final long HEARTBEAT_INTERVAL_MS = 30_000; // 30s
  private static final long EMITTER_TIMEOUT_MS = 3600_000; // 1 hour

  private final Map<String, SseEmitter> checkEmitters = new ConcurrentHashMap<>();
  private final Map<UUID, SseEmitter> dashboardEmitters = new ConcurrentHashMap<>();
  private final ProjectRepository projects;
  private final CheckRunService checkRunService;
  private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

  public CheckRunSseService(ProjectRepository projects, CheckRunService checkRunService) {
    this.projects = projects;
    this.checkRunService = checkRunService;

    // Heartbeat task
    heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_INTERVAL_MS,
        HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  /**
   * Subscribes to check run updates for a project.
   */
  public SseEmitter subscribe(UUID userId, UUID projectId) {
    String key = userId + ":" + projectId;
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

    emitter.onCompletion(() -> checkEmitters.remove(key));
    emitter.onTimeout(() -> checkEmitters.remove(key));
    emitter.onError(e -> {
      log.debug("SSE error for {}: {}", key, e.toString());
      checkEmitters.remove(key);
    });

    // Send initial state
    try {
      var runs = checkRunService.getLatestCheckRuns(userId, projectId);
      emitter.send(SseEmitter.event()
          .name("init")
          .data(runs.stream().map(this::toCheckEvent).toList()));
    } catch (IOException e) {
      log.debug("Failed to send initial state: {}", e.toString());
    }

    checkEmitters.put(key, emitter);
    return emitter;
  }

  /**
   * Subscribes to dashboard summary updates.
   */
  public SseEmitter subscribeDashboard(UUID userId) {
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

    emitter.onCompletion(() -> dashboardEmitters.remove(userId));
    emitter.onTimeout(() -> dashboardEmitters.remove(userId));
    emitter.onError(e -> {
      log.debug("Dashboard SSE error for {}: {}", userId, e.toString());
      dashboardEmitters.remove(userId);
    });

    // Send initial state
    try {
      var summary = checkRunService.getDashboardSummary(userId);
      emitter.send(SseEmitter.event().name("init").data(summary));
    } catch (IOException e) {
      log.debug("Failed to send initial dashboard state: {}", e.toString());
    }

    dashboardEmitters.put(userId, emitter);
    return emitter;
  }

  /**
   * Notifies all subscribers of a check run update.
   */
  public void notifyCheckRunUpdate(UUID userId, UUID projectId, CheckRun run) {
    String key = userId + ":" + projectId;
    SseEmitter emitter = checkEmitters.get(key);
    if (emitter != null) {
      try {
        emitter.send(SseEmitter.event()
            .name("update")
            .data(toCheckEvent(run)));
      } catch (IOException e) {
        log.debug("Failed to send SSE update: {}", e.toString());
        checkEmitters.remove(key);
      }
    }
  }

  /**
   * Notifies all dashboard subscribers of an update.
   */
  public void notifyDashboardUpdate(UUID userId) {
    SseEmitter emitter = dashboardEmitters.get(userId);
    if (emitter != null) {
      try {
        var summary = checkRunService.getDashboardSummary(userId);
        emitter.send(SseEmitter.event().name("update").data(summary));
      } catch (IOException e) {
        log.debug("Failed to send dashboard SSE update: {}", e.toString());
        dashboardEmitters.remove(userId);
      }
    }
  }

  /**
   * Broadcasts a check run update to all subscribers of that project.
   */
  public void broadcastCheckRunUpdate(UUID projectId, CheckRun run) {
    // Find all users who own this project
    Project project = projects.findById(projectId).orElse(null);
    if (project == null) return;

    UUID ownerId = project.getOwner().getId();
    String key = ownerId + ":" + projectId;
    SseEmitter emitter = checkEmitters.get(key);
    if (emitter != null) {
      try {
        emitter.send(SseEmitter.event()
            .name("update")
            .data(toCheckEvent(run)));
      } catch (IOException e) {
        checkEmitters.remove(key);
      }
    }
  }

  /**
   * Broadcasts dashboard update to all subscribers of a user.
   */
  public void broadcastDashboardUpdate(UUID userId) {
    SseEmitter emitter = dashboardEmitters.get(userId);
    if (emitter != null) {
      try {
        var summary = checkRunService.getDashboardSummary(userId);
        emitter.send(SseEmitter.event().name("update").data(summary));
      } catch (IOException e) {
        dashboardEmitters.remove(userId);
      }
    }
  }

  private void sendHeartbeats() {
    Instant now = Instant.now();
    for (Map.Entry<String, SseEmitter> entry : checkEmitters.entrySet()) {
      try {
        entry.getValue().send(SseEmitter.event().name("heartbeat").data(now.toString()));
      } catch (IOException e) {
        checkEmitters.remove(entry.getKey());
      }
    }
    for (Map.Entry<UUID, SseEmitter> entry : dashboardEmitters.entrySet()) {
      try {
        entry.getValue().send(SseEmitter.event().name("heartbeat").data(now.toString()));
      } catch (IOException e) {
        dashboardEmitters.remove(entry.getKey());
      }
    }
  }

  private Object toCheckEvent(CheckRun run) {
    return new java.util.LinkedHashMap<>(Map.ofEntries(
        Map.entry("id", run.getId()),
        Map.entry("feature", run.getFeature().name()),
        Map.entry("status", run.getStatus().name()),
        Map.entry("progress", run.getProgress()),
        Map.entry("currentStep", run.getCurrentStep()),
        Map.entry("errorMessage", run.getErrorMessage()),
        Map.entry("startedAt", run.getStartedAt()),
        Map.entry("finishedAt", run.getFinishedAt()),
        Map.entry("durationMs", run.getDurationMs()),
        Map.entry("severityCritical", run.getSeverityCritical()),
        Map.entry("severityHigh", run.getSeverityHigh()),
        Map.entry("severityMedium", run.getSeverityMedium()),
        Map.entry("severityLow", run.getSeverityLow()),
        Map.entry("severityInfo", run.getSeverityInfo())
    ));
  }
}