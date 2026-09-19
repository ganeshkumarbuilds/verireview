package com.verireview.agent;

import com.verireview.agent.dto.AiCodingFinding;
import com.verireview.agent.dto.AiCodingRequest;
import com.verireview.agent.dto.AiCodingResult;
import com.verireview.agent.dto.AiFileSnapshot;
import com.verireview.fix.FixRequest;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.review.Finding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI-backed Coding Agent (Phase 9D). Replaces the deterministic placeholder.
 * Sends scoped finding + file snapshots to the Python AI service and returns
 * the validated diff proposal. Never writes files, never claims verification.
 * If the AI service is unavailable or returns malformed diff, throws
 * AiServiceException for controlled failure (no fake patch).
 */
@Component
public class DeterministicCodingAgent implements CodingAgent {

  private static final Logger log = LoggerFactory.getLogger(DeterministicCodingAgent.class);

  private final CodingAiClient codingClient;
  private final AiServiceProperties props;
  private final ProjectFileRepository projectFiles;
  private final ProjectStorage storage;

  public DeterministicCodingAgent(
      CodingAiClient codingClient,
      AiServiceProperties props,
      ProjectFileRepository projectFiles,
      ProjectStorage storage) {
    this.codingClient = codingClient;
    this.props = props;
    this.projectFiles = projectFiles;
    this.storage = storage;
  }

  @Override
  public Proposal propose(FixRequest fixRequest) {
    if (!props.enabled()) {
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE, "AI coding service disabled");
    }

    Finding finding = fixRequest.getFinding();
    Project project = finding.getReview().getProject();

    List<AiFileSnapshot> snapshots = loadSnapshots(project);
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

    AiCodingRequest request = new AiCodingRequest(
        fixRequest.getId().toString(),
        codingFinding,
        fixRequest.getScopeNote(),
        project.getLanguage(),
        snapshots,
        "coding-" + fixRequest.getId());

    AiCodingResult result = codingClient.coding(request);

    // Re-validate diff strictly before returning (defense in depth)
    String diff = result.diff() != null ? result.diff().trim() : "";
    if (diff.isBlank() || !diff.contains("diff --git") || !diff.contains("---") || !diff.contains("+++") || !diff.contains("@@")) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED, "AI coding returned invalid diff");
    }
    if (diff.toLowerCase().contains("verified") || diff.toLowerCase().contains("tests passed") || diff.toLowerCase().contains("build passed")) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED, "AI diff must not claim verification");
    }

    return new Proposal(
        diff,
        Math.max(0, result.filesChanged()),
        Math.max(0, result.additions()),
        Math.max(0, result.deletions()));
  }

  private List<AiFileSnapshot> loadSnapshots(Project project) {
    List<ProjectFile> all = new ArrayList<>(projectFiles.findByProjectId(project.getId()));
    all.sort(Comparator.comparingLong(ProjectFile::getSizeBytes));
    List<AiFileSnapshot> snapshots = new ArrayList<>();
    Path projectDir = project.getStorageRef() != null ? Paths.get(project.getStorageRef()) : null;
    for (ProjectFile file : all) {
      if (snapshots.size() >= props.maxFiles()) break;
      AiFileSnapshot snap = snapshotFile(projectDir, file);
      if (snap != null) snapshots.add(snap);
    }
    return snapshots;
  }

  private AiFileSnapshot snapshotFile(Path projectDir, ProjectFile file) {
    String content = file.getContent();
    boolean truncated = false;
    if (content == null && projectDir != null && file.getPath() != null) {
      content = readDiskFile(projectDir, file.getPath());
    }
    if (content == null) return null;
    if (content.indexOf('\0') >= 0) return null;
    long cap = props.maxBytesPerFile();
    if (content.length() > cap) {
      content = content.substring(0, (int) cap) + "\n[... truncated by the backend; full file omitted ...]";
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
      byte[] bytes = Files.readAllBytes(resolved);
      if (bytes.length == 0) return "";
      for (byte b : bytes) if (b == 0) return null;
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("Skipping unreadable file for AI coding context: {}", relativePath);
      return null;
    }
  }
}
