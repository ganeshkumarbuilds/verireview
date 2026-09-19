package com.verireview.analysis;

import com.verireview.agent.AiReviewService;
import com.verireview.agent.AiServiceProperties;
import com.verireview.agent.ReviewAiClient;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.audit.AuditService;
import com.verireview.execution.SandboxRunner;
import com.verireview.review.ReviewStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async analysis worker. Runs outside any database transaction (the sandbox
 * run takes minutes); it crosses into short {@link AnalysisJobService}
 * transactions only to flip review state and persist findings.
 */
@Component
public class AnalysisRunner {

  private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

  private final AnalysisJobService jobs;
  private final SandboxRunner sandbox;
  private final AuditService audits;
  private final boolean dependencyCheckEnabled;
  private final AiServiceProperties aiProps;
  private final AiReviewService aiReviews;
  private final ReviewAiClient aiClient;

  public AnalysisRunner(
      @Lazy AnalysisJobService jobs,
      SandboxRunner sandbox,
      AuditService audits,
      @Value("${app.analysis.dependency-check-enabled:false}") boolean dependencyCheckEnabled,
      AiServiceProperties aiProps,
      AiReviewService aiReviews,
      ReviewAiClient aiClient) {
    this.jobs = jobs;
    this.sandbox = sandbox;
    this.audits = audits;
    this.dependencyCheckEnabled = dependencyCheckEnabled;
    this.aiProps = aiProps;
    this.aiReviews = aiReviews;
    this.aiClient = aiClient;
  }

  @Async("analysisExecutor")
  public void runAsync(UUID reviewId) {
    Instant started = Instant.now();
    AnalysisJobService.ReviewSnapshot snapshot = jobs.snapshot(reviewId);
    if (snapshot == null) {
      return;
    }
    jobs.markRunning(reviewId, started);
    SandboxRunner.SandboxWorkspace workspace = null;
    try {
      Path projectDir = snapshot.storageRef() == null ? null : Path.of(snapshot.storageRef());
      workspace = sandbox.analyzeSnapshot(projectDir);
      List<ToolReport> reports = collectReports(workspace);
      int stored = jobs.persistFindings(reviewId, reports);
      AiReviewService.AiOutcome aiOutcome = runAiStep(reviewId);
      String notes = combineNotes(toolNotes(reports), aiOutcome.note());
      jobs.finish(reviewId, started, ReviewStatus.COMPLETED, stored + aiOutcome.added(),
          notes.isEmpty() ? null : notes);
      audits.record(null, "ANALYSIS_COMPLETED", "review", reviewId.toString());
    } catch (Exception e) {
      log.warn("Analysis {} failed: {}", reviewId, e.toString());
      jobs.finish(reviewId, started, ReviewStatus.FAILED, 0, truncate(e.getMessage(), 2000));
      audits.record(null, "ANALYSIS_FAILED", "review", reviewId.toString());
    } finally {
      sandbox.deleteWorkspace(workspace);
    }
  }

  private List<ToolReport> collectReports(SandboxRunner.SandboxWorkspace workspace) {
    Path out = workspace.outputDir();
    Path snapshot = workspace.snapshotDir();
    Map<String, String> statuses = readStatuses(out.resolve("tool-status.properties"));
    List<ToolReport> reports = new ArrayList<>();
    reports.add(reportOf("checkstyle", statuses,
        CheckstyleParser.parse(out.resolve("checkstyle.xml"), snapshot)));
    reports.add(reportOf("pmd", statuses,
        PmdParser.parse(out.resolve("pmd.xml"), snapshot)));
    reports.add(reportOf("spotbugs", statuses,
        SpotbugsParser.parse(out.resolve("spotbugs.xml"), snapshot)));
    if (dependencyCheckEnabled) {
      reports.add(reportOf("dependency-check", statuses,
          DependencyCheckParser.parse(out.resolve("dependency-check.json"), snapshot)));
    } else {
      reports.add(ToolReport.skipped("dependency-check",
          ToolReport.ToolStatus.SKIPPED_DISABLED,
          "Dependency-Check needs an NVD mirror; enable app.analysis.dependency-check-enabled explicitly"));
    }
    assertPinnedVersions(out);
    return reports;
  }

  private static ToolReport reportOf(
      String analyzer, Map<String, String> statuses, List<NormalizedFinding> parsed) {
    String status = statuses.getOrDefault(analyzer, "RAN");
    return switch (status) {
      case "RAN" -> ToolReport.ran(analyzer, parsed);
      case "FAILED" -> ToolReport.failed(analyzer, "Tool reported a failure; no report produced");
      default -> ToolReport.skipped(analyzer, ToolReport.ToolStatus.valueOf(status),
          "Tool skipped: " + status);
    };
  }

  /**
   * AI review step: scoped input is assembled and sent in one transaction,
   * the HTTP call runs outside any transaction (bounded by client timeouts),
   * and validation/persistence happen in a second short transaction.
   * Any AI failure degrades to deterministic-only — it never fails the review.
   */
  private AiReviewService.AiOutcome runAiStep(UUID reviewId) {
    if (!aiProps.enabled()) {
      return AiReviewService.AiOutcome.skipped("AI review disabled");
    }
    AiReviewService.AiContext ctx = aiReviews.prepare(reviewId);
    long callStart = System.nanoTime();
    try {
      AiReviewResult result = aiClient.review(ctx.toRequest());
      return aiReviews.complete(ctx, result, elapsedMs(callStart));
    } catch (com.verireview.agent.AiServiceException e) {
      return aiReviews.fail(ctx, e.getMessage(), elapsedMs(callStart));
    }
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static String combineNotes(String toolNotes, String aiNote) {
    if (aiNote == null || aiNote.isBlank()) {
      return toolNotes;
    }
    return toolNotes.isEmpty() ? aiNote : toolNotes + "; " + aiNote;
  }

  private static String toolNotes(List<ToolReport> reports) {    List<String> notes = new ArrayList<>();
    for (ToolReport report : reports) {
      if (report.status() != ToolReport.ToolStatus.RAN) {
        notes.add(report.analyzer() + ": " + report.status()
            + (report.detail() == null ? "" : " (" + report.detail() + ")"));
      }
    }
    return String.join("; ", notes);
  }

  private static Map<String, String> readStatuses(Path file) {
    Map<String, String> statuses = new LinkedHashMap<>();
    try {
      if (Files.isRegularFile(file)) {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
          properties.load(in);
        }
        properties.forEach((key, value) -> statuses.put(String.valueOf(key),
            String.valueOf(value).trim().toUpperCase()));
      }
    } catch (Exception e) {
      log.warn("Could not read tool statuses: {}", e.toString());
    }
    return statuses;
  }

  /** Fails the run when the sandbox image is not the pinned one. */
  private static void assertPinnedVersions(Path out) {
    try {
      Path versions = out.resolve("versions.properties");
      if (!Files.isRegularFile(versions)) {
        throw new IllegalStateException("Sandbox image omitted versions.properties");
      }
      Properties properties = new Properties();
      try (var in = Files.newInputStream(versions)) {
        properties.load(in);
      }
      requirePin(properties, "checkstyle", ToolVersions.CHECKSTYLE);
      requirePin(properties, "pmd", ToolVersions.PMD);
      requirePin(properties, "spotbugs", ToolVersions.SPOTBUGS);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Could not verify sandbox tool versions", e);
    }
  }

  private static void requirePin(Properties properties, String tool, String expected) {
    String actual = properties.getProperty(tool, "").trim();
    if (!expected.equals(actual)) {
      throw new IllegalStateException(
          "Sandbox " + tool + " version " + actual + " does not match pin " + expected);
    }
  }

  private static String truncate(String message, int max) {
    if (message == null) {
      return "Analysis failed";
    }
    return message.length() <= max ? message : message.substring(0, max);
  }
}
