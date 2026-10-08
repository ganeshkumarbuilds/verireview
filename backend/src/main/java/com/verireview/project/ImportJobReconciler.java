package com.verireview.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles stale import jobs on application startup.
 * - Jobs in EXTRACTING/INDEXING from a previous process are marked FAILED.
 * - Jobs in QUEUED older than 2 minutes:
 *   - If staged file exists and is readable, re-dispatch for processing.
 *   - If staged file missing (ephemeral disk wiped) or not readable, mark FAILED with "Server restarted, please retry".
 */
@Component
public class ImportJobReconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ImportJobReconciler.class);
  private static final Duration QUEUED_STALE_THRESHOLD = Duration.ofMinutes(2);

  private final ImportJobRepository jobs;
  private final ApplicationEventPublisher eventPublisher;

  public ImportJobReconciler(ImportJobRepository jobs, ApplicationEventPublisher eventPublisher) {
    this.jobs = jobs;
    this.eventPublisher = eventPublisher;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    reconcileStaleJobs();
  }

  @Transactional
  public void reconcileStaleJobs() {
    Instant now = Instant.now();

    // 1. Handle EXTRACTING/INDEXING - always mark as FAILED (were in progress when server stopped)
    List<ImportJob> inProgressJobs = jobs.findByStatusIn(
        List.of(ImportJobStatus.EXTRACTING, ImportJobStatus.INDEXING));
    if (!inProgressJobs.isEmpty()) {
      log.warn("Found {} stale import jobs in EXTRACTING/INDEXING state, marking as FAILED",
          inProgressJobs.size());
      for (ImportJob job : inProgressJobs) {
        job.setStatus(ImportJobStatus.FAILED);
        job.setErrorMessage("Import interrupted by application restart (was " + job.getStatus() + ")");
        job.setCurrentStep("Import failed: interrupted by restart");
        job.setFinishedAt(now);
        if (job.getStartedAt() != null) {
          job.setDurationMs(Duration.between(job.getStartedAt(), now).toMillis());
        }
      }
      jobs.saveAll(inProgressJobs);
    }

    // 2. Handle QUEUED jobs older than threshold
    List<ImportJob> queuedJobs = jobs.findByStatusIn(List.of(ImportJobStatus.QUEUED));
    if (!queuedJobs.isEmpty()) {
      for (ImportJob job : queuedJobs) {
        Duration age = Duration.between(job.getCreatedAt(), now);
        if (age.compareTo(QUEUED_STALE_THRESHOLD) > 0) {
          String stagedPath = job.getStagedFilePath();
          boolean stagedFileExists = stagedPath != null && Files.exists(Path.of(stagedPath));
          boolean stagedFileReadable = stagedFileExists && Files.isReadable(Path.of(stagedPath));

          if (stagedFileReadable) {
            // Re-dispatch for processing by publishing event
            try {
              long stagedSize = Files.size(Path.of(stagedPath));
              log.info("Re-dispatching stale QUEUED job {} (age={}, staged file exists at {}, size={} bytes)",
                  job.getId(), age, stagedPath, stagedSize);
            } catch (IOException e) {
              log.info("Re-dispatching stale QUEUED job {} (age={}, staged file exists at {})",
                  job.getId(), age, stagedPath);
            }
            eventPublisher.publishEvent(new ImportJobEvent(job.getId(), job.getOwner().getId(), ImportJobEvent.ImportJobType.ZIP));
            // Job stays QUEUED, worker will pick it up
          } else {
            // Staged file missing (likely ephemeral disk wiped on restart) or not readable
            String reason = stagedFileExists
                ? "staged file exists but is not readable"
                : "staged file missing (ephemeral disk likely wiped on restart)";
            log.warn("Marking QUEUED job {} as FAILED: age={}, {}",
                job.getId(), age, reason);
            job.setStatus(ImportJobStatus.FAILED);
            job.setErrorMessage("Server restarted and staged file was lost, please retry upload");
            job.setCurrentStep("Import failed: server restart lost staged file, please retry");
            job.setFinishedAt(now);
            jobs.save(job);
          }
        }
      }
    }
  }
}