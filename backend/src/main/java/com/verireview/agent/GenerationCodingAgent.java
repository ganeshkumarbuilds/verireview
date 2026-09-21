package com.verireview.agent;

import com.verireview.agent.dto.AiCodingFinding;
import com.verireview.agent.dto.AiCodingRequest;
import com.verireview.agent.dto.AiCodingResult;
import com.verireview.agent.dto.AiFileSnapshot;
import com.verireview.fix.FixRequest;
import com.verireview.fix.UnifiedDiffValidator;
import com.verireview.generation.Generation;
import com.verireview.generation.GenerationService;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.ProjectFile;
import com.verireview.review.Finding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generation Coding Agent for generation fix workflow (Task 5).
 * Reads scoped finding + file snapshots from the GENERATION WORKSPACE
 * (not a project) and sends to the Python AI service. Returns validated
 * diff proposal. Never writes files, never claims verification.
 */
@Component
public class GenerationCodingAgent implements CodingAgent {

  private static final Logger log = LoggerFactory.getLogger(GenerationCodingAgent.class);

  private final CodingAiClient codingClient;
  private final AiServiceProperties props;
  private final GenerationService generationService;
  private final ProjectStorage storage;

  public GenerationCodingAgent(
      CodingAiClient codingClient,
      AiServiceProperties props,
      GenerationService generationService,
      ProjectStorage storage) {
    this.codingClient = codingClient;
    this.props = props;
    this.generationService = generationService;
    this.storage = storage;
  }

  @Override
  public Proposal propose(FixRequest fixRequest) {
    if (!props.enabled()) {
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE, "AI coding service disabled");
    }

    Finding finding = fixRequest.getFinding();
    // Generation finding: the review is linked to a generation, not a project
    Generation generation = finding.getReview().getGeneration();
    if (generation == null) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED, "FixRequest finding is not linked to a generation");
    }

    List<AiFileSnapshot> snapshots = loadGenerationSnapshots(generation);
    AiCodingFinding codingFinding = new AiCodingFinding(
        finding.getId().toString(),
        finding.getTitle() != null ? finding.getTitle() : "fix",
        finding.getDescription(),
        finding.getFilePath(),
        finding.getLineStart(),
        finding.getLineEnd(),
        finding.getCategory() != null ? finding.getCategory().name() : "CODE_QUALITY",
        finding.getSeverity() != null ? finding.getSeverity().name() : "MEDIUM",
        finding.getEvidence());

    // Use generation id + iteration for idempotency
    String idempotencyKey = "gen-coding-" + generation.getId() + "-" + generation.getIteration() + "-" + fixRequest.getId();

    AiCodingRequest request = new AiCodingRequest(
        fixRequest.getId().toString(),
        codingFinding,
        fixRequest.getScopeNote(),
        primaryLanguage(generation),
        snapshots,
        idempotencyKey);

    AiCodingResult result = codingClient.coding(request);

    // Re-validate strictly before returning (defense in depth)
    final UnifiedDiffValidator.DiffStats stats;
    try {
      stats = UnifiedDiffValidator.validate(result.diff());
    } catch (UnifiedDiffValidator.DiffValidationException e) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED, e.getMessage());
    }

    return new Proposal(
        result.diff().trim(),
        stats.filesChanged(),
        stats.additions(),
        stats.deletions());
  }

  private String primaryLanguage(Generation generation) {
    return switch (generation.getBackendStack()) {
      case JAVA_SPRING_BOOT -> "java";
      case PYTHON_FASTAPI -> "python";
      case NODEJS -> "javascript";
    };
  }

  private List<AiFileSnapshot> loadGenerationSnapshots(Generation generation) {
    // Read files from the generation workspace for the current iteration
    Path workspace = storage.generationWorkspaceDir(generation.getId(), generation.getIteration());
    List<AiFileSnapshot> snapshots = new ArrayList<>();
    try {
      if (Files.exists(workspace)) {
        Files.walk(workspace)
            .filter(Files::isRegularFile)
            .forEach(file -> {
              try {
                String relativePath = workspace.relativize(file).toString();
                String content = Files.readString(file);
                if (content.indexOf('\0') < 0 && !content.isBlank()) {
                  snapshots.add(new AiFileSnapshot(
                      relativePath,
                      languageOf(relativePath),
                      content,
                      false));
                }
              } catch (Exception ignored) {
                // Skip unreadable files
              }
            });
      }
    } catch (Exception ignored) {
      // Return empty list on error
    }
    // Sort by size (smallest first) to prioritize important files within cap
    List<AiFileSnapshot> sorted = new ArrayList<>(snapshots);
    sorted.sort(Comparator.comparingInt(s -> s.content().length()));
    // Cap at max files
    if (sorted.size() > props.maxFiles()) {
      return sorted.subList(0, props.maxFiles());
    }
    return sorted;
  }

  private String languageOf(String path) {
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot == path.length() - 1) {
      return null;
    }
    String ext = path.substring(dot + 1).toLowerCase();
    return switch (ext) {
      case "java" -> "java";
      case "py" -> "python";
      case "js", "jsx" -> "javascript";
      case "ts", "tsx" -> "typescript";
      case "sql" -> "sql";
      case "xml" -> "xml";
      case "yml", "yaml" -> "yaml";
      case "json" -> "json";
      case "md" -> "markdown";
      case "html" -> "html";
      case "css" -> "css";
      default -> null;
    };
  }
}