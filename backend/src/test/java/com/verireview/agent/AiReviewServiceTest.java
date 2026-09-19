package com.verireview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verireview.agent.dto.AiFileSnapshot;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewRequest;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.audit.AuditService;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 7C service tests. Repositories, client, and audit are mocked;
 * storage is real (temp dir). No Spring context, no network, no database.
 */
class AiReviewServiceTest {

  @TempDir
  Path temp;

  private static final ObjectMapper OBJECTS = new ObjectMapper();

  private final ReviewRepository reviews = mock(ReviewRepository.class);
  private final FindingRepository findings = mock(FindingRepository.class);
  private final ProjectFileRepository projectFiles = mock(ProjectFileRepository.class);
  private final AgentExecutionRepository executions = mock(AgentExecutionRepository.class);
  private final AuditService audits = mock(AuditService.class);
  private final ReviewAiClient client = mock(ReviewAiClient.class);

  private final UUID reviewId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final AtomicReference<AgentExecution> savedExecution = new AtomicReference<>();

  @BeforeEach
  void stubExecutions() {
    when(executions.save(any(AgentExecution.class))).thenAnswer(invocation -> {
      AgentExecution execution = invocation.getArgument(0);
      // Unit-test entities are never persisted (no DB ids); assign one so
      // lookups behave like production rows.
      try {
        java.lang.reflect.Field id =
            com.verireview.common.BaseEntity.class.getDeclaredField("id");
        id.setAccessible(true);
        if (id.get(execution) == null) {
          id.set(execution, UUID.randomUUID());
        }
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
      savedExecution.set(execution);
      return execution;
    });
    when(executions.findById(any()))
        .thenAnswer(invocation -> Optional.ofNullable(savedExecution.get()));
  }

  private AiReviewService service(ProjectStorage storage) {
    AiServiceProperties props = new AiServiceProperties(
        "http://localhost:8001", "", 5, true, "ai-service/review", 25, 16384);
    return new AiReviewService(
        props, reviews, findings, projectFiles, executions, storage, audits, OBJECTS);
  }

  private ProjectStorage storage() {
    return new ProjectStorage(temp.resolve("store").toString());
  }

  private Review reviewWith(Project project) {
    Review review = mock(Review.class);
    when(review.getId()).thenReturn(reviewId);
    when(review.getProject()).thenReturn(project);
    return review;
  }

  private Project project(String storageRef) {
    Project project = mock(Project.class);
    when(project.getId()).thenReturn(projectId);
    when(project.getLanguage()).thenReturn("java");
    when(project.getStorageRef()).thenReturn(storageRef);
    return project;
  }

  private ProjectFile inlineFile(String path, String content) {
    ProjectFile file = new ProjectFile(null, path, content.length(), "0".repeat(64));
    file.setContent(content);
    file.setLanguage("java");
    return file;
  }

  private Finding deterministic(String title) {
    Finding finding = new Finding(
        mock(Review.class), FindingCategory.STYLE, FindingSeverity.MEDIUM,
        FindingSource.DETERMINISTIC, title);
    finding.setDescription("too long");
    finding.setFilePath("Main.java");
    finding.setLineStart(3);
    finding.setEvidence("{\"analyzer\": \"checkstyle\", \"rule\": \"LineLength\"}");
    return finding;
  }

  private AiReviewResult aiResult(AiProposedFinding... items) {
    return new AiReviewResult(reviewId.toString(), "review", "review/v1",
        List.of(items), "notes", 0);
  }

  private AiProposedFinding aiFinding(String title, String source) {
    return new AiProposedFinding("BUG", "HIGH", title, "desc", "Main.java",
        10, 12, "evidence", source, "hint", 0.9);
  }

  @Test
  @SuppressWarnings("unchecked")
  void happyPathPersistsAiFindingsAndCoercesVerified() {
    Review review = reviewWith(project(null));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(review));
    when(projectFiles.findByProjectId(projectId)).thenReturn(List.of());
    when(findings.findByReviewId(reviewId)).thenReturn(List.of());
    when(findings.existsByReviewIdAndDedupKey(any(), any())).thenReturn(false);
    when(findings.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(client.review(any(AiReviewRequest.class))).thenReturn(
        aiResult(aiFinding("Real bug", "AI"), aiFinding("Bold claim", "VERIFIED")));

    AiReviewService service = service(storage());
    AiReviewService.AiContext ctx = service.prepare(reviewId);
    AiReviewService.AiOutcome outcome =
        service.complete(ctx, client.review(ctx.toRequest()), 1234L);

    assertThat(outcome.added()).isEqualTo(2);
    assertThat(outcome.attempted()).isTrue();
    assertThat(outcome.note()).contains("coerced");

    ArgumentCaptor<List<Finding>> saved = ArgumentCaptor.forClass(List.class);
    verify(findings).saveAll(saved.capture());
    assertThat(saved.getValue()).hasSize(2);
    assertThat(saved.getValue())
        .allSatisfy(row -> assertThat(row.getSource()).isEqualTo(FindingSource.AI));

    ArgumentCaptor<AgentExecution> executionsSaved =
        ArgumentCaptor.forClass(AgentExecution.class);
    verify(executions).save(executionsSaved.capture());
    assertThat(executionsSaved.getValue().getStatus())
        .isEqualTo(AgentExecutionStatus.COMPLETED);
    assertThat(executionsSaved.getValue().getPromptVersion()).isEqualTo("review/v1");
    verify(audits).record(
        org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("AI_REVIEW_COMPLETED"),
        org.mockito.ArgumentMatchers.eq("review"), org.mockito.ArgumentMatchers.eq(reviewId.toString()));
  }

  @Test
  void deterministicEchoesAndInvalidProposalsAreSkipped() {
    Review review = reviewWith(project(null));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(review));
    when(projectFiles.findByProjectId(projectId)).thenReturn(List.of());
    when(findings.findByReviewId(reviewId)).thenReturn(List.of());
    when(findings.existsByReviewIdAndDedupKey(any(), any())).thenReturn(false);

    AiReviewResult result = new AiReviewResult(reviewId.toString(), "review", "review/v1",
        List.of(
            aiFinding("Echo", "DETERMINISTIC"),
            new AiProposedFinding("NOPE", "HIGH", "Bad enum", "d", "F.java",
                1, 1, "e", "AI", "", 0.5),
            new AiProposedFinding("BUG", "HIGH", "  ", "d", "F.java",
                1, 1, "e", "AI", "", 0.5)),
        "", 2);

    AiReviewService service = service(storage());
    AiReviewService.AiOutcome outcome = service.complete(
        service.prepare(reviewId), result, 10L);

    assertThat(outcome.added()).isEqualTo(0);
    verify(findings, never()).saveAll(any());
  }

  @Test
  void existingDedupKeysAreSkipped() {
    Review review = reviewWith(project(null));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(review));
    when(projectFiles.findByProjectId(projectId)).thenReturn(List.of());
    when(findings.findByReviewId(reviewId)).thenReturn(List.of());
    when(findings.existsByReviewIdAndDedupKey(any(), any())).thenReturn(true);

    AiReviewService service = service(storage());
    AiReviewService.AiOutcome outcome = service.complete(
        service.prepare(reviewId), aiResult(aiFinding("Dup", "AI")), 10L);

    assertThat(outcome.added()).isEqualTo(0);
  }

  @Test
  void aiFailureDegradesWithoutTouchingFindings() {
    Review review = reviewWith(project(null));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(review));
    when(projectFiles.findByProjectId(projectId)).thenReturn(List.of());
    when(findings.findByReviewId(reviewId)).thenReturn(List.of());
    AgentExecution execution = new AgentExecution(
        mock(Project.class), AgentType.REVIEW, "ai-service/review", "unknown", "hash");
    when(executions.save(any(AgentExecution.class))).thenReturn(execution);
    when(executions.findById(any())).thenReturn(Optional.of(execution));

    AiReviewService service = service(storage());
    AiReviewService.AiContext ctx = service.prepare(reviewId);
    AiReviewService.AiOutcome outcome = service.fail(ctx, "timed out", 5000L);

    assertThat(outcome.attempted()).isTrue();
    assertThat(outcome.added()).isEqualTo(0);
    assertThat(outcome.note()).contains("timed out");
    assertThat(execution.getStatus()).isEqualTo(AgentExecutionStatus.FAILED);
    verify(findings, never()).saveAll(any());
    verify(audits).record(
        org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("AI_REVIEW_FAILED"),
        org.mockito.ArgumentMatchers.eq("review"), org.mockito.ArgumentMatchers.eq(reviewId.toString()));
  }

  @Test
  void prepareCapsTruncatesAndSkipsUnreadable() throws Exception {
    Path projectDir = temp.resolve("proj");
    Files.createDirectories(projectDir);
    Files.writeString(projectDir.resolve("disk.java"), "class Disk {}");
    // Missing on disk and no inline content.
    List<ProjectFile> files = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      files.add(inlineFile("f" + i + ".java", "x".repeat(10)));
    }
    ProjectFile big = inlineFile("big.java", "y".repeat(20000));
    ProjectFile binary = inlineFile("blob.bin", "ab\0cd");
    ProjectFile disk = new ProjectFile(null, "disk.java", 20L, "1".repeat(64));
    disk.setLanguage("java");
    ProjectFile ghost = new ProjectFile(null, "ghost.java", 5L, "2".repeat(64));
    ghost.setLanguage("java");
    files.add(big);
    files.add(binary);
    files.add(disk);
    files.add(ghost);

    Review review = reviewWith(project(projectDir.toString()));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(review));
    when(projectFiles.findByProjectId(projectId)).thenReturn(files);
    when(findings.findByReviewId(reviewId)).thenReturn(List.of(deterministic("LineLength")));
    when(executions.save(any(AgentExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AiReviewService.AiContext ctx = service(storage()).prepare(reviewId);

    assertThat(ctx.files()).hasSize(22);
    assertThat(ctx.files()).noneMatch(f -> f.path().equals("blob.bin"));
    assertThat(ctx.files()).noneMatch(f -> f.path().equals("ghost.java"));
    AiFileSnapshot bigSnapshot = ctx.files().stream()
        .filter(f -> f.path().equals("big.java"))
        .findFirst()
        .orElseThrow();
    assertThat(bigSnapshot.truncated()).isTrue();
    assertThat(bigSnapshot.content()).contains("truncated by the backend");
    AiFileSnapshot diskSnapshot = ctx.files().stream()
        .filter(f -> f.path().equals("disk.java"))
        .findFirst()
        .orElseThrow();
    assertThat(diskSnapshot.content()).isEqualTo("class Disk {}");
    assertThat(diskSnapshot.truncated()).isFalse();
    assertThat(ctx.deterministic()).hasSize(1);
    assertThat(ctx.deterministic().get(0).tool()).isEqualTo("checkstyle");
    assertThat(ctx.deterministic().get(0).ruleId()).isEqualTo("LineLength");
    assertThat(ctx.inputHash()).hasSize(64);
  }

  @Test
  void snapshotOrderingPrefersSmallFiles() {
    List<ProjectFile> files = List.of(
        inlineFile("big.java", "x".repeat(5000)),
        inlineFile("small.java", "y"));
    Review review = reviewWith(project(null));
    when(reviews.findById(reviewId)).thenReturn(Optional.of(review));
    when(projectFiles.findByProjectId(projectId)).thenReturn(files);
    when(findings.findByReviewId(reviewId)).thenReturn(List.of());
    when(executions.save(any(AgentExecution.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    AiReviewService.AiContext ctx = service(storage()).prepare(reviewId);

    assertThat(ctx.files()).extracting("path")
        .containsExactly("small.java", "big.java");
  }
}
