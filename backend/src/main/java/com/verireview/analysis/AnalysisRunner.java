package com.verireview.analysis;

import com.verireview.agent.AiReviewService;
import com.verireview.agent.AiServiceProperties;
import com.verireview.agent.ReviewAiClient;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.audit.AuditService;
import com.verireview.execution.SandboxRunner;
import com.verireview.generation.GenerationStaticAnalyzer;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async analysis worker. Runs outside any database transaction (the sandbox
 * run takes minutes); it crosses into short {@link AnalysisJobService}
 * transactions only to flip review state and persist findings.
 *
 * <p>Deterministic tools (Checkstyle, PMD, SpotBugs, Dependency-Check) all
 * run inside the Docker sandbox. When {@code app.sandbox.docker-enabled} is
 * false -- a deployment host without Docker-in-Docker support -- every
 * deterministic tool is reported {@code SKIPPED_DISABLED} instead of being
 * silently omitted or crashing the review, exactly like the existing
 * Dependency-Check "needs an NVD mirror" skip. The AI Review Agent step is
 * unaffected: it is a plain HTTP call to ai-service with no Docker
 * dependency, so uploaded projects still get a real (AI-only) review.
 */
@Component
public class AnalysisRunner {

  private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

  private final AnalysisJobService jobs;
  private final SandboxRunner sandbox;
  private final AuditService audits;
  private final boolean dependencyCheckEnabled;
  private final boolean dockerEnabled;
  private final AiServiceProperties aiProps;
  private final AiReviewService aiReviews;
  private final ReviewAiClient aiClient;
  private final GenerationStaticAnalyzer staticAnalyzer;
  private final ProjectFileRepository projectFiles;
  private final ProjectStorage storage;

  @Autowired
  public AnalysisRunner(
      @Lazy AnalysisJobService jobs,
      SandboxRunner sandbox,
      AuditService audits,
      @Value("${app.analysis.dependency-check-enabled:false}") boolean dependencyCheckEnabled,
      @Value("${app.sandbox.docker-enabled:true}") boolean dockerEnabled,
      AiServiceProperties aiProps,
      AiReviewService aiReviews,
      ReviewAiClient aiClient,
      GenerationStaticAnalyzer staticAnalyzer,
      ProjectFileRepository projectFiles,
      ProjectStorage storage) {
    this.jobs = jobs;
    this.sandbox = sandbox;
    this.audits = audits;
    this.dependencyCheckEnabled = dependencyCheckEnabled;
    this.dockerEnabled = dockerEnabled;
    this.aiProps = aiProps;
    this.aiReviews = aiReviews;
    this.aiClient = aiClient;
    this.staticAnalyzer = staticAnalyzer;
    this.projectFiles = projectFiles;
    this.storage = storage;
  }

  public AnalysisRunner(
      @Lazy AnalysisJobService jobs,
      SandboxRunner sandbox,
      AuditService audits,
      boolean dependencyCheckEnabled,
      boolean dockerEnabled,
      AiServiceProperties aiProps,
      AiReviewService aiReviews,
      ReviewAiClient aiClient) {
    this(jobs, sandbox, audits, dependencyCheckEnabled, dockerEnabled, aiProps, aiReviews,
        aiClient, null, null, null);
  }

  @Async("analysisExecutor")
  public void runAsync(UUID reviewId) {
    runAsync(reviewId, null);
  }

  @Async("analysisExecutor")
  public void runAsync(UUID reviewId, AnalysisJobService.ReviewCredentials credentials) {
    Instant started = Instant.now();
    AnalysisJobService.ReviewSnapshot snapshot = jobs.snapshot(reviewId);
    if (snapshot == null) {
      return;
    }
    jobs.markRunning(reviewId, started);
    SandboxRunner.SandboxWorkspace workspace = null;
    try {
      List<ToolReport> reports;
      boolean analyzed;
      int inProcessFindings = 0;
      if (dockerEnabled) {
        Path projectDir = snapshot.storageRef() == null ? null : Path.of(snapshot.storageRef());
        workspace = sandbox.analyzeSnapshot(projectDir);
        reports = collectReports(workspace);
        analyzed = reports.stream().anyMatch(report ->
            report.status() == ToolReport.ToolStatus.RAN);
      } else {
        log.warn("Skipping deterministic analysis tools for review {}: Docker is disabled "
            + "in this environment. Running in-process file analysis.", reviewId);
        reports = skippedReports();
        InProcessOutcome outcome = runInProcessAnalysis(reviewId, snapshot);
        analyzed = outcome.analyzed();
        inProcessFindings = outcome.findings();
      }
      int stored = jobs.persistFindings(reviewId, reports) + inProcessFindings;
      AiReviewService.AiOutcome aiOutcome = runAiStep(reviewId, credentials);
      String notes = combineNotes(toolNotes(reports), aiOutcome.note());
      ReviewStatus finalStatus = analyzed ? ReviewStatus.COMPLETED : ReviewStatus.NOT_ANALYZED;
      String finalNotes = analyzed
          ? notes
          : "No checks could run: sandbox unavailable";
      jobs.finish(reviewId, started, finalStatus, stored + aiOutcome.added(),
          finalNotes.isEmpty() ? null : finalNotes, reports);
      audits.record(null, "ANALYSIS_COMPLETED", "review", reviewId.toString());
    } catch (Exception e) {
      log.warn("Analysis {} failed: {}", reviewId, e.toString());
      jobs.finish(reviewId, started, ReviewStatus.FAILED, 0, truncate(e.getMessage(), 2000));
      audits.record(null, "ANALYSIS_FAILED", "review", reviewId.toString());
    } finally {
      sandbox.deleteWorkspace(workspace);
    }
  }

  private InProcessOutcome runInProcessAnalysis(
        UUID reviewId, AnalysisJobService.ReviewSnapshot snapshot) {
      if (staticAnalyzer == null || projectFiles == null) {
        return new InProcessOutcome(false, 0);
      }
      List<ProjectFile> files = new ArrayList<>(projectFiles.findByProjectId(snapshot.projectId()));
      files.sort(java.util.Comparator.comparing(ProjectFile::getPath));
      boolean analyzed = false;
      int findings = 0;
      for (ProjectFile file : files) {
        String path = file.getPath();
        if (ignored(path)) {
          jobs.recordFileResult(reviewId, path, AnalysisFileStatus.SKIPPED,
              "Ignored by repository analysis rules", file.getSizeBytes(), 0);
          continue;
        }
        if (file.getSizeBytes() > 100_000) {
          jobs.recordFileResult(reviewId, path, AnalysisFileStatus.SKIPPED,
              "File exceeds the 100 KB in-process analysis limit", file.getSizeBytes(), 0);
          continue;
        }
        String content = readFile(snapshot, file);
        if (content == null) {
          jobs.recordFileResult(reviewId, path, AnalysisFileStatus.SKIPPED,
              "File content was unavailable or binary", file.getSizeBytes(), 0);
          continue;
        }
        List<GenerationStaticAnalyzer.StaticFinding> hits = staticAnalyzer.analyze(
            List.of(new AiGenerationFilesResult.GeneratedFile(path, content, file.getLanguage())));
        int added = jobs.persistStaticFindings(reviewId, hits);
        findings += added;
        jobs.recordFileResult(reviewId, path, AnalysisFileStatus.ANALYZED, null,
            file.getSizeBytes(), added);
        analyzed = true;
      }
      return new InProcessOutcome(analyzed, findings);
    }

    private record InProcessOutcome(boolean analyzed, int findings) {
    }

    private String readFile(AnalysisJobService.ReviewSnapshot snapshot, ProjectFile file) {
      if (file.getContent() != null) {
        return file.getContent();
      }
      if (storage == null || snapshot.storageRef() == null) {
        return null;
      }
      try {
        Path root = Path.of(snapshot.storageRef());
        Path resolved = storage.resolveJailed(root, file.getPath());
        byte[] bytes = Files.readAllBytes(resolved);
        for (byte value : bytes) {
          if (value == 0) return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      } catch (Exception e) {
        return null;
      }
    }

    private static boolean ignored(String path) {
      if (path == null || path.isBlank()) return true;
      String normalized = path.replace('\\', '/');
      return java.util.Arrays.stream(normalized.split("/"))
          .anyMatch(part -> java.util.Set.of(
              ".git", "node_modules", "target", "build", "dist", ".venv",
              "venv", "__pycache__", ".idea", ".vscode", "coverage").contains(part));
    }
  /**
   * Reports used in place of real tool output when Docker is disabled.
   * Every deterministic tool is explicitly SKIPPED_DISABLED -- never silently
   * absent, and never reported as if it ran and found nothing.
   */
  private List<ToolReport> skippedReports() {
    String reason = "Docker sandbox is disabled in this environment; deterministic "
        + "analysis tools were not run";
    List<ToolReport> reports = new ArrayList<>();
    reports.add(ToolReport.skipped("checkstyle", ToolReport.ToolStatus.SKIPPED_DISABLED, reason));
    reports.add(ToolReport.skipped("pmd", ToolReport.ToolStatus.SKIPPED_DISABLED, reason));
    reports.add(ToolReport.skipped("spotbugs", ToolReport.ToolStatus.SKIPPED_DISABLED, reason));
    reports.add(ToolReport.skipped("dependency-check", ToolReport.ToolStatus.SKIPPED_DISABLED,
        dependencyCheckEnabled ? reason
            : "Dependency-Check needs an NVD mirror; enable app.analysis.dependency-check-enabled explicitly"));
    return reports;
  }

  private List<ToolReport> collectReports(SandboxRunner.SandboxWorkspace workspace) {
    Path out = workspace.outputDir();
    Path snapshot = workspace.snapshotDir();
    Map<String, String> statuses = readStatuses(out.resolve("tool-status.properties"));
    List<ToolReport> reports = new ArrayList<>();
    long checkstyleStarted = System.nanoTime();
    reports.add(reportOf("checkstyle", statuses,
        CheckstyleParser.parse(out.resolve("checkstyle.xml"), snapshot),
        elapsedMs(checkstyleStarted)));
    long pmdStarted = System.nanoTime();
    reports.add(reportOf("pmd", statuses,
        PmdParser.parse(out.resolve("pmd.xml"), snapshot), elapsedMs(pmdStarted)));
    long spotbugsStarted = System.nanoTime();
    reports.add(reportOf("spotbugs", statuses,
        SpotbugsParser.parse(out.resolve("spotbugs.xml"), snapshot),
        elapsedMs(spotbugsStarted)));
    if (dependencyCheckEnabled) {
      long dependencyStarted = System.nanoTime();
      reports.add(reportOf("dependency-check", statuses,
          DependencyCheckParser.parse(out.resolve("dependency-check.json"), snapshot),
          elapsedMs(dependencyStarted)));
    } else {
      reports.add(ToolReport.skipped("dependency-check",
          ToolReport.ToolStatus.SKIPPED_DISABLED,
          "Dependency-Check needs an NVD mirror; enable app.analysis.dependency-check-enabled explicitly"));
    }
    assertPinnedVersions(out);
    return reports;
  }

  private static ToolReport reportOf(
      String analyzer, Map<String, String> statuses, List<NormalizedFinding> parsed,
      long durationMs) {
    String status = statuses.getOrDefault(analyzer, "RAN");
    return switch (status) {
      case "RAN" -> ToolReport.ran(analyzer, parsed).withDuration(durationMs);
      case "FAILED" -> ToolReport.failed(analyzer, "Tool reported a failure; no report produced");
      default -> ToolReport.skipped(analyzer, ToolReport.ToolStatus.valueOf(status),
          "Tool skipped: " + status);
    };
  }

  /**
   * AI review step: scoped input is assembled and sent in one transaction,
   * the HTTP call runs outside any transaction (bounded by client timeouts),
   * and validation/persistence happen in a second short transaction.
   * Any AI failure degrades to deterministic-only -- it never fails the review.
   */
  private AiReviewService.AiOutcome runAiStep(
      UUID reviewId, AnalysisJobService.ReviewCredentials credentials) {
    if (credentials != null && (credentials.apiKey() == null || credentials.apiKey().isBlank())) {
      return AiReviewService.AiOutcome.skipped("AI review skipped: no key provided");
    }
    if (credentials == null && !aiProps.enabled()) {
      return AiReviewService.AiOutcome.skipped("AI review disabled");
    }
    AiReviewService.AiContext ctx = aiReviews.prepare(reviewId);
    long callStart = System.nanoTime();
    try {
      AiReviewResult result = aiClient.review(
          credentials == null ? ctx.toRequest() : ctx.toRequest(credentials));
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