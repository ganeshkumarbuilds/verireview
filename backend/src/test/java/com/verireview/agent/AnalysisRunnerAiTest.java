package com.verireview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verireview.agent.dto.AiReviewResult;
import com.verireview.analysis.AnalysisJobService;
import com.verireview.analysis.AnalysisRunner;
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

/**
 * Phase 7C wiring tests: the runner sums deterministic + AI counts, merges
 * notes, and skips the AI step when disabled. Sandbox, jobs, and AI are
 * mocked — no Docker, no network, no database.
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
    return new AnalysisRunner(
        jobs, sandbox, audits, false, aiProps, aiReviews, aiClient);
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
        total.capture(), notes.capture());
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
        total.capture(), notes.capture());
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
        total.capture(), any());
    assertThat(total.getValue()).isEqualTo(1);
  }
}
