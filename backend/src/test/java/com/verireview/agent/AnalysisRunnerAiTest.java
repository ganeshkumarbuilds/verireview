package com.verireview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;

import com.verireview.agent.dto.AiReviewResult;
import com.verireview.analysis.AnalysisJobService;
import com.verireview.analysis.AnalysisRunner;
import com.verireview.analysis.ToolReport;
import com.verireview.analysis.AnalysisFileStatus;
import com.verireview.generation.GenerationStaticAnalyzer;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.analysis.ToolVersions;
import com.verireview.audit.AuditService;
import com.verireview.execution.SandboxRunner;
import com.verireview.review.ReviewStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 7C wiring tests: the runner sums deterministic + AI counts, merges
 * notes, and skips the AI step when disabled. Sandbox, jobs, and AI are
 * mocked — no Docker, no network, no database.
 *
 * <p>dockerEnabled is passed as true here: these tests exercise the real
 * sandbox-analysis code path (via a mocked SandboxRunner) and assert that
 * sandbox.analyzeSnapshot is actually invoked, which only happens when
 * Docker is enabled.
 */
class AnalysisRunnerAiTest {

  @TempDir
  Path temp;

  private final AnalysisJobService jobs = mock(AnalysisJobService.class);
  private final SandboxRunner sandbox = mock(SandboxRunner.class);
  private final AuditService audits = mock(AuditService.class);
  private final AiServiceProperties aiProps = mock(AiServiceProperties.class);
  private final AiReviewService aiReviews = mock(AiReviewService.class);
  private final ReviewAiClient aiClient = mock(ReviewAiClient.class);

  private final UUID reviewId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();

  private AnalysisRunner runner() {
    return runner(true);
  }

  private AnalysisRunner runner(boolean dockerEnabled) {
    return new AnalysisRunner(
        jobs, sandbox, audits, false, dockerEnabled, aiProps, aiReviews, aiClient);
  }

  private AnalysisRunner inProcessRunner(ProjectFileRepository files) {
    return new AnalysisRunner(
        jobs, sandbox, audits, false, false, aiProps, aiReviews, aiClient,
        new GenerationStaticAnalyzer(new ObjectMapper()), files, mock(ProjectStorage.class));
  }

  private ProjectFile file(String path, String content, long size) {
    ProjectFile file = mock(ProjectFile.class);
    when(file.getPath()).thenReturn(path);
    when(file.getContent()).thenReturn(content);
    when(file.getSizeBytes()).thenReturn(size);
    when(file.getLanguage()).thenReturn(null);
    return file;
  }

  private SandboxRunner.SandboxWorkspace workspace() throws Exception {
    Path work = Files.createDirectories(temp.resolve("work-" + UUID.randomUUID()));
    Path snapshot = Files.createDirectories(work.resolve("src"));
    Path output = Files.createDirectories(work.resolve("out"));
    Files.writeString(output.resolve("versions.properties"),
        "checkstyle=" + ToolVersions.CHECKSTYLE + "\n"
            + "pmd=" + ToolVersions.PMD + "\n"
            + "spotbugs=" + ToolVersions.SPOTBUGS + "\n");
    return new SandboxRunner.SandboxWorkspace(snapshot, output);
  }

  private AiReviewService.AiContext context() {
    return new AiReviewService.AiContext(
        reviewId, projectId, UUID.randomUUID(), "java",
        List.of(), List.of(), "hash");
  }

  @Test
  void aiFindingsAreAddedToTheReviewTotal() throws Exception {
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(sandbox.analyzeSnapshot(any())).thenReturn(workspace());
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(3);
    when(aiProps.enabled()).thenReturn(true);
    AiReviewService.AiContext ctx = context();
    when(aiReviews.prepare(reviewId)).thenReturn(ctx);
    AiReviewResult result = new AiReviewResult(
        reviewId.toString(), "review", "review/v1", List.of(), "", 0);
    when(aiClient.review(any())).thenReturn(result);
    when(aiReviews.complete(eq(ctx), eq(result), any(Long.class)))
        .thenReturn(AiReviewService.AiOutcome.completed(2, 0, "AI review: 2 new findings"));

    runner().runAsync(reviewId);

    ArgumentCaptor<Integer> total = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.COMPLETED),
        total.capture(), notes.capture(), any());
    assertThat(total.getValue()).isEqualTo(5);
    assertThat(notes.getValue()).contains("AI review: 2 new findings");
    verify(audits).record(
        org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.eq("ANALYSIS_COMPLETED"),
        org.mockito.ArgumentMatchers.eq("review"),
        org.mockito.ArgumentMatchers.eq(reviewId.toString()));
  }

  @Test
  void aiFailureDegradesToDeterministicOnly() throws Exception {
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(sandbox.analyzeSnapshot(any())).thenReturn(workspace());
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(4);
    when(aiProps.enabled()).thenReturn(true);
    AiReviewService.AiContext ctx = context();
    when(aiReviews.prepare(reviewId)).thenReturn(ctx);
    when(aiClient.review(any()))
        .thenThrow(new AiServiceException(AiServiceException.Kind.TIMEOUT, "timed out"));
    when(aiReviews.fail(eq(ctx), any(String.class), any(Long.class)))
        .thenReturn(AiReviewService.AiOutcome.failed("AI review skipped: timed out"));

    runner().runAsync(reviewId);

    ArgumentCaptor<Integer> total = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.COMPLETED),
        total.capture(), notes.capture(), any());
    assertThat(total.getValue()).isEqualTo(4);
    assertThat(notes.getValue()).contains("AI review skipped");
  }

  @Test
  void disabledAiStepSkipsWithoutCalls() throws Exception {
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(sandbox.analyzeSnapshot(any())).thenReturn(workspace());
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(1);
    when(aiProps.enabled()).thenReturn(false);

    runner().runAsync(reviewId);

    verify(aiReviews, never()).prepare(any());
    verify(aiClient, never()).review(any());
    ArgumentCaptor<Integer> total = ArgumentCaptor.forClass(Integer.class);
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.COMPLETED),
        total.capture(), any(), any());
    assertThat(total.getValue()).isEqualTo(1);
  }

  @Test
  void allDeterministicChecksSkippedIsNotAnalyzed() {
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(aiProps.enabled()).thenReturn(false);
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(0);

    runner(false).runAsync(reviewId);

    ArgumentCaptor<List> reports = ArgumentCaptor.forClass(List.class);
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.NOT_ANALYZED),
        eq(0), eq("No checks could run: sandbox unavailable"), reports.capture());
    assertThat(reports.getValue()).hasSize(4)
        .allSatisfy(value -> assertThat(((ToolReport) value).status())
            .isEqualTo(ToolReport.ToolStatus.SKIPPED_DISABLED));
    verify(sandbox, never()).analyzeSnapshot(any());
  }

  @Test
  void ranCheckWithNoFindingsCompletesAndRecordsEvidence() throws Exception {
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(sandbox.analyzeSnapshot(any())).thenReturn(workspace());
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(0);
    when(aiProps.enabled()).thenReturn(false);

    runner().runAsync(reviewId);

    ArgumentCaptor<List> reports = ArgumentCaptor.forClass(List.class);
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.COMPLETED),
        eq(0), any(), reports.capture());
    assertThat(reports.getValue()).anySatisfy(value -> {
      ToolReport report = (ToolReport) value;
      assertThat(report.analyzer()).isEqualTo("checkstyle");
      assertThat(report.status()).isEqualTo(ToolReport.ToolStatus.RAN);
      assertThat(report.findings()).isEmpty();
      assertThat(report.durationMs()).isGreaterThanOrEqualTo(0);
    });
  }

  @Test
  void inProcessAnalysisFindsSyntaxLogicSecurityAndStyleAndSkipsAiWithoutKey() {
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    ProjectFile main = file("Main.java", "class Broken {", 14);
    ProjectFile python = file("app.py", "if user = None:\n    pass\n", 25);
    ProjectFile javascript = file("config.js", "const apiKey = \"abcd1234-secret\";\n", 38);
    ProjectFile typescript = file("src.ts", "const count = 1; \n", 20);
    when(files.findByProjectId(projectId)).thenReturn(List.of(main, python, javascript, typescript));
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(jobs.persistStaticFindings(eq(reviewId), any())).thenReturn(1);
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(0);

    inProcessRunner(files).runAsync(reviewId, AnalysisJobService.ReviewCredentials.none());

    verify(jobs, times(4)).recordFileResult(
        eq(reviewId), any(String.class), eq(AnalysisFileStatus.ANALYZED),
        eq(null), anyLong(), eq(1));
    verify(aiClient, never()).review(any());
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.COMPLETED),
        eq(4), contains("AI review skipped: no key provided"), any());
  }

  @Test
  void inProcessAnalysisRecordsIgnoredAndOversizedFiles() {
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    ProjectFile ignored = file("node_modules/pkg/index.js", "const x = {", 12);
    ProjectFile oversized = file("large.js", "const x = 1;", 100_001);
    when(files.findByProjectId(projectId)).thenReturn(List.of(ignored, oversized));
    when(jobs.snapshot(reviewId)).thenReturn(
        new AnalysisJobService.ReviewSnapshot(reviewId, projectId, null, UUID.randomUUID()));
    when(jobs.persistFindings(eq(reviewId), any())).thenReturn(0);

    inProcessRunner(files).runAsync(reviewId, AnalysisJobService.ReviewCredentials.none());

    verify(jobs).recordFileResult(eq(reviewId), eq("node_modules/pkg/index.js"),
        eq(AnalysisFileStatus.SKIPPED), contains("Ignored"), eq(12L), eq(0));
    verify(jobs).recordFileResult(eq(reviewId), eq("large.js"),
        eq(AnalysisFileStatus.SKIPPED), contains("100 KB"), eq(100_001L), eq(0));
    verify(jobs).finish(eq(reviewId), any(Instant.class), eq(ReviewStatus.NOT_ANALYZED),
        eq(0), eq("No checks could run: sandbox unavailable"), any());
  }
}