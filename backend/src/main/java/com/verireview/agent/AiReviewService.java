package com.verireview.agent;

import com.verireview.agent.dto.AiDeterministicFinding;
import com.verireview.agent.dto.AiFileSnapshot;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewRequest;
import com.verireview.agent.dto.AiReviewResult;
import com.verireview.analysis.Fingerprint;
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
import com.verireview.review.FindingStatus;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Backend side of the Review Agent loop (Phase 7C). The backend stays
 * authoritative: it assembles least-context input, records the
 * {@link AgentExecution} trace, validates every AI proposal, persists only
 * {@code AI} rows, and degrades to deterministic-only on any AI failure.
 *
 * <p>Transaction discipline: {@link #prepare} and {@link #complete}/{@link #fail}
 * are short, separate transactions (called cross-bean by the runner); the
 * HTTP call itself never holds a database connection.
 */
@Service
public class AiReviewService {

  private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

  /** Detached, serializable input for one AI invocation. */
  public record AiContext(
      UUID reviewId,
      UUID projectId,
      UUID executionId,
      String language,
      List<AiFileSnapshot> files,
      List<AiDeterministicFinding> deterministic,
      String inputHash) {

    public AiReviewRequest toRequest() {      return new AiReviewRequest(
          reviewId.toString(), projectId.toString(), language,
          files, deterministic, "review-" + reviewId);
    }
  }

  /** Outcome summary merged into the review notes by the runner. */
  public record AiOutcome(int added, int skipped, String note, boolean attempted) {

    public static AiOutcome skipped(String note) {
      return new AiOutcome(0, 0, note, false);
    }

    public static AiOutcome completed(int added, int skipped, String note) {
      return new AiOutcome(added, skipped, note, true);
    }

    public static AiOutcome failed(String note) {
      return new AiOutcome(0, 0, note, true);
    }
  }

  private final AiServiceProperties props;
  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final ProjectFileRepository projectFiles;
  private final AgentExecutionRepository executions;
  private final ProjectStorage storage;
  private final AuditService audits;
  private final ObjectMapper objects;

  public AiReviewService(
      AiServiceProperties props,
      ReviewRepository reviews,
      FindingRepository findings,
      ProjectFileRepository projectFiles,
      AgentExecutionRepository executions,
      ProjectStorage storage,
      AuditService audits,
      ObjectMapper objects) {
    this.props = props;
    this.reviews = reviews;
    this.findings = findings;
    this.projectFiles = projectFiles;
    this.executions = executions;
    this.storage = storage;
    this.audits = audits;
    this.objects = objects;
  }

  /**
   * Loads scoped input and opens the RUNNING execution row.
   * Skips files it cannot safely read (binary, unreadable, oversized).
   */
  @Transactional
  public AiContext prepare(UUID reviewId) {
    Review review = reviews.findById(reviewId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
    Project project = review.getProject();
    List<ProjectFile> all = new ArrayList<>(projectFiles.findByProjectId(project.getId()));
    all.sort(Comparator.comparingLong(ProjectFile::getSizeBytes));
    List<AiFileSnapshot> snapshots = new ArrayList<>();
    Path projectDir = project.getStorageRef() == null
        ? null : Paths.get(project.getStorageRef());
    for (ProjectFile file : all) {
      if (snapshots.size() >= props.maxFiles()) {
        break;
      }
      AiFileSnapshot snapshot = snapshotFile(projectDir, file);
      if (snapshot != null) {
        snapshots.add(snapshot);
      }
    }
    List<AiDeterministicFinding> deterministic = new ArrayList<>();
    for (Finding finding : findings.findByReviewId(review.getId())) {
      deterministic.add(new AiDeterministicFinding(
          toolOf(finding), ruleOf(finding), finding.getFilePath(),
          finding.getLineStart(), finding.getDescription()));
    }
    String inputHash = sha256Hex(reviewId + "|" + project.getId() + "|"
        + snapshots.size() + "|" + deterministic.size());
    AgentExecution execution = new AgentExecution(
        project, AgentType.REVIEW, props.modelLabel(), "unknown", inputHash);
    execution.setStatus(AgentExecutionStatus.RUNNING);
    execution.setReview(review);
    executions.save(execution);
    return new AiContext(review.getId(), project.getId(), execution.getId(),
        project.getLanguage(), snapshots, deterministic, inputHash);
  }

  /**
   * Validates one AI proposal batch and persists the {@code AI} rows.
   * Deterministic echoes are skipped (the backend already owns ground truth);
   * a {@code VERIFIED} source from the model is coerced to {@code AI} —
   * the backend never lets AI self-verify.
   */
  @Transactional
  public AiOutcome complete(AiContext ctx, AiReviewResult result, long durationMs) {
    AgentExecution execution =
        executions.findById(ctx.executionId()).orElseThrow();
    List<Finding> rows = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int skippedInvalid = 0;
    int skippedEchoes = 0;
    boolean verifiedCoerced = false;
    Review review = reviews.findById(ctx.reviewId()).orElseThrow();
    for (AiProposedFinding item : result.findings()) {
      if (!"AI".equalsIgnoreCase(item.source())
          && !"VERIFIED".equalsIgnoreCase(item.source())) {
        if ("DETERMINISTIC".equalsIgnoreCase(item.source())) {
          skippedEchoes++;
          continue;
        }
        skippedInvalid++;
        continue;
      }
      FindingCategory category;
      FindingSeverity severity;
      try {
        category = FindingCategory.valueOf(item.category().trim().toUpperCase());
        severity = FindingSeverity.valueOf(item.severity().trim().toUpperCase());
      } catch (Exception e) {
        skippedInvalid++;
        continue;
      }
      if (item.title() == null || item.title().isBlank()) {
        skippedInvalid++;
        continue;
      }
      if ("VERIFIED".equalsIgnoreCase(item.source())) {
        verifiedCoerced = true;
      }
      String title = trim(item.title(), 500);
      String filePath = trim(item.filePath(), 1000);
      Integer lineStart = nonNegative(item.lineStart());
      Integer lineEnd = nonNegative(item.lineEnd());
      String key = Fingerprint.of(
          "review-agent", title, filePath, lineStart, item.description());
      if (!seen.add(key) || findings.existsByReviewIdAndDedupKey(review.getId(), key)) {
        continue;
      }
      Finding row = new Finding(review, category, severity, FindingSource.AI, title);
      row.setDescription(trim(item.description(), 5000));
      row.setFilePath(filePath);
      row.setLineStart(lineStart);
      row.setLineEnd(lineEnd);
      row.setEvidence(evidenceJson(item, result.promptVersion()));
      row.setDedupKey(key);
      row.setStatus(FindingStatus.OPEN);
      rows.add(row);
    }
    if (!rows.isEmpty()) {
      findings.saveAll(rows);
    }
    execution.setStatus(AgentExecutionStatus.COMPLETED);
    execution.setDurationMs(durationMs);
    execution.setPromptVersion(
        result.promptVersion() == null || result.promptVersion().isBlank()
            ? "unknown" : result.promptVersion());
    execution.setOutputRef("review:" + ctx.reviewId() + " ai:" + rows.size()
        + " invalid-skipped:" + skippedInvalid + " echoes-skipped:" + skippedEchoes);
    String note = "AI review: " + rows.size() + " new findings"
        + (skippedInvalid > 0 ? ", " + skippedInvalid + " invalid proposals skipped" : "")
        + (verifiedCoerced ? "; model-declared VERIFIED coerced to AI" : "");
    audits.record(null, "AI_REVIEW_COMPLETED", "review", ctx.reviewId().toString());
    return AiOutcome.completed(rows.size(), skippedInvalid + skippedEchoes, note);
  }

  /** Marks the execution failed; the review keeps deterministic findings. */
  @Transactional
  public AiOutcome fail(AiContext ctx, String reason, long durationMs) {
    executions.findById(ctx.executionId()).ifPresent(execution -> {
      execution.setStatus(AgentExecutionStatus.FAILED);
      execution.setDurationMs(durationMs);
      execution.setError(reason == null || reason.length() <= 2000
          ? reason : reason.substring(0, 2000));
    });
    audits.record(null, "AI_REVIEW_FAILED", "review", ctx.reviewId().toString());
    return AiOutcome.failed("AI review skipped: " + reason);
  }

  private AiFileSnapshot snapshotFile(Path projectDir, ProjectFile file) {
    String content = file.getContent();
    boolean truncated = false;
    if (content == null && projectDir != null && file.getPath() != null) {
      content = readDiskFile(projectDir, file.getPath());
    }
    if (content == null) {
      return null;
    }
    if (content.indexOf('\0') >= 0) {
      return null;
    }
    long cap = props.maxBytesPerFile();
    if (content.length() > cap) {
      content = content.substring(0, (int) cap)
          + "\n[... truncated by the backend; full file omitted ...]";
      truncated = true;
    }
    return new AiFileSnapshot(file.getPath(), file.getLanguage(), content, truncated);
  }

  private String readDiskFile(Path projectDir, String relativePath) {
    final Path resolved;
    try {
      resolved = storage.resolveJailed(projectDir, relativePath);
    } catch (RuntimeException e) {
      return null;
    }
    try {
      byte[] bytes = java.nio.file.Files.readAllBytes(resolved);
      if (bytes.length == 0) {
        return "";
      }
      for (byte b : bytes) {
        if (b == 0) {
          return null;
        }
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("Skipping unreadable file for AI context: {}", relativePath);
      return null;
    }
  }

  private String toolOf(Finding finding) {
    String analyzer = evidenceField(finding.getEvidence(), "analyzer");
    return analyzer == null || analyzer.isBlank() ? "unknown" : analyzer;
  }

  private String ruleOf(Finding finding) {
    String rule = evidenceField(finding.getEvidence(), "rule");
    if (rule != null && !rule.isBlank()) {
      return rule;
    }
    return finding.getTitle() == null ? "" : finding.getTitle();
  }

  private String evidenceField(String evidenceJson, String field) {
    if (evidenceJson == null || evidenceJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = objects.readTree(evidenceJson);
      JsonNode child = node == null ? null : node.get(field);
      if (child == null || child.isNull()) {
        return null;
      }
      return child.asText();
    } catch (Exception e) {
      return null;
    }
  }

  private String evidenceJson(AiProposedFinding item, String promptVersion) {
    try {
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("analyzer", "review-agent");
      evidence.put("model", props.modelLabel());
      evidence.put("promptVersion", promptVersion);
      evidence.put("confidence", item.confidence());
      evidence.put("suggestedFixHint", trim(item.suggestedFixHint(), 2000));
      evidence.put("claimedSource", item.source());
      return objects.writeValueAsString(evidence);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static Integer nonNegative(Integer value) {
    return value != null && value >= 0 ? value : null;
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
