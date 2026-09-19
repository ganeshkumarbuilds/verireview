package com.verireview.fix;

import com.verireview.audit.AuditService;
import com.verireview.fix.dto.PatchResponse;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controlled patch application (Phase 10A). Applies a PROPOSED patch to a
 * project snapshot directory with strict validation, jailed paths, backup,
 * and atomicity. Never runs code or builds.
 */
@Service
public class PatchApplicationService {

  private static final Pattern HUNK_HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

  private final PatchRepository patches;
  private final PatchService patchService;
  private final ProjectStorage storage;
  private final AuditService audits;

  public PatchApplicationService(
      PatchRepository patches,
      PatchService patchService,
      ProjectStorage storage,
      AuditService audits) {
    this.patches = patches;
    this.patchService = patchService;
    this.storage = storage;
    this.audits = audits;
  }

  @Transactional
  public PatchResponse applyPatch(UUID ownerId, UUID patchId) {
    Patch patch = ownedPatch(ownerId, patchId);

    if (patch.getStatus() != PatchStatus.PROPOSED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Patch must be in PROPOSED state to be applied");
    }

    Project project = patch.getProject() != null
        ? patch.getProject()
        : patch.getFixRequest().getFinding().getReview().getProject();

    // Owner check already done via ownedPatch, but double-check project not deleted
    if (project.getDeletedAt() != null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }

    Path projectDir = storage.projectDir(project.getId());
    if (!Files.isDirectory(projectDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project storage not found");
    }

    String diff = patch.getDiff();
    // Strict validation before any FS mutation
    List<FilePatch> filePatches = parseAndValidate(projectDir, diff);

    // Create backup snapshot
    Path backupDir = null;
    try {
      backupDir = Files.createTempDirectory("patch-backup-" + patchId);
      Path backupSnapshot = backupDir.resolve("snapshot");
      copyDirectory(projectDir, backupSnapshot);

      // Apply each file patch
      try {
        for (FilePatch fp : filePatches) {
          applyFilePatch(projectDir, fp);
        }
      } catch (ResponseStatusException e) {
        // Restore on failure
        restoreBackup(projectDir, backupSnapshot);
        // Keep patch non-APPLIED, record validation error if needed but don't change status
        throw e;
      } catch (Exception e) {
        restoreBackup(projectDir, backupSnapshot);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to apply patch: " + e.getMessage());
      }

      // Success: update status to APPLIED
      patch.setStatus(PatchStatus.APPLIED);
      patches.save(patch);

      audits.record(
          project.getOwner(),
          "PATCH_APPLIED",
          "patch",
          patch.getId().toString());

      return PatchService.toResponse(patch);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create backup: " + e.getMessage());
    } finally {
      if (backupDir != null) {
        storage.deleteQuietly(backupDir);
      }
    }
  }

  private Patch ownedPatch(UUID ownerId, UUID patchId) {
    Patch patch = patches.findById(patchId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found"));
    Project project = patch.getFixRequest() != null && patch.getFixRequest().getFinding() != null
        ? patch.getFixRequest().getFinding().getReview().getProject()
        : patch.getProject();
    if (project == null) {
      // fallback via patch.project
      project = patch.getProject();
    }
    if (project == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found");
    }
    Project ownerProject = patch.getFixRequest().getFinding().getReview().getProject();
    if (ownerProject.getDeletedAt() != null || !ownerProject.getOwner().getId().equals(ownerId)) {
      // Also check direct project link if present
      if (patch.getProject() != null) {
        if (patch.getProject().getDeletedAt() != null || !patch.getProject().getOwner().getId().equals(ownerId)) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found");
        }
      } else {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found");
      }
    }
    // Ensure we use the canonical owner check via FixRequest path
    Project canonical = patch.getFixRequest().getFinding().getReview().getProject();
    if (canonical.getDeletedAt() != null || !canonical.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found");
    }
    return patch;
  }

  private List<FilePatch> parseAndValidate(Path projectDir, String diff) {
    if (diff == null || diff.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff is empty");
    }
    String normalized = diff.replace("\r\n", "\n");
    if (!normalized.contains("diff --git")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: missing diff --git header");
    }
    // Split by diff --git
    String[] blocks = normalized.split("(?=diff --git )");
    List<FilePatch> result = new ArrayList<>();
    for (String block : blocks) {
      if (block.isBlank()) continue;
      if (!block.startsWith("diff --git ")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: invalid file header");
      }
      // Extract paths from diff --git a/... b/...
      String firstLine = block.lines().findFirst().orElse("");
      String[] parts = firstLine.split(" ");
      if (parts.length < 4) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: invalid diff --git line");
      }
      String aPath = parts[2];
      String bPath = parts[3];
      if (!aPath.startsWith("a/") || !bPath.startsWith("b/")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: expected a/ and b/ prefixes");
      }
      String relativeA = aPath.substring(2);
      String relativeB = bPath.substring(2);
      if (!relativeA.equals(relativeB)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff a/b paths must match");
      }
      String relative = relativeA;

      // Reject absolute, traversal, etc. via jailed resolver
      // This also validates the path is inside project
      try {
        storage.resolveJailed(projectDir, relative);
      } catch (ResponseStatusException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff contains illegal path: " + relative);
      }

      // Validate that block contains --- and +++ and @@
      if (!block.contains("\n--- ") && !block.contains("--- a/")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: missing --- header for " + relative);
      }
      if (!block.contains("\n+++ ") && !block.contains("+++ b/")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: missing +++ header for " + relative);
      }
      if (!block.contains("@@")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed diff: missing hunk header for " + relative);
      }

      // Validate --- and +++ paths match diff --git path
      // Find --- and +++ lines
      String[] lines = block.split("\n");
      String minusFile = null;
      String plusFile = null;
      for (String line : lines) {
        if (line.startsWith("--- ")) {
          minusFile = line.substring(4).trim().split("\\s+")[0];
          if (minusFile.startsWith("a/")) minusFile = minusFile.substring(2);
          else if (minusFile.equals("/dev/null")) minusFile = relative; // allow new file deletion case? but we treat as valid
        } else if (line.startsWith("+++ ")) {
          plusFile = line.substring(4).trim().split("\\s+")[0];
          if (plusFile.startsWith("b/")) plusFile = plusFile.substring(2);
          else if (plusFile.equals("/dev/null")) plusFile = relative;
        }
      }
      if (minusFile != null && !minusFile.equals(relative) && !minusFile.equals("/dev/null")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff header mismatch for " + relative);
      }
      if (plusFile != null && !plusFile.equals(relative) && !plusFile.equals("/dev/null")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff header mismatch for " + relative);
      }

      // Validate hunks
      List<Hunk> hunks = new ArrayList<>();
      Hunk current = null;
      for (String line : lines) {
        if (line.startsWith("@@")) {
          Matcher m = HUNK_HEADER.matcher(line);
          if (!m.matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed hunk header: " + line);
          }
          current = new Hunk(line);
          hunks.add(current);
        } else if (current != null) {
          // Inside hunk body - validate line prefix
          if (line.isEmpty()) {
            // Empty line is context? In unified diff, empty context not typical but treat as context
            current.lines.add(line);
          } else {
            char c = line.charAt(0);
            if (c != ' ' && c != '+' && c != '-' && c != '\\') {
              // Allow unexpected? But strict: only these prefixes
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed hunk line: " + line);
            }
            current.lines.add(line);
          }
        }
      }
      if (hunks.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff contains no hunks for " + relative);
      }

      result.add(new FilePatch(relative, hunks));
    }
    if (result.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Diff contains no file patches");
    }
    return result;
  }

  private void applyFilePatch(Path projectDir, FilePatch filePatch) throws IOException {
    Path target = storage.resolveJailed(projectDir, filePatch.relativePath);
    List<String> originalLines;
    if (Files.exists(target)) {
      if (!Files.isRegularFile(target)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target is not a regular file: " + filePatch.relativePath);
      }
      String content = Files.readString(target, StandardCharsets.UTF_8);
      originalLines = new ArrayList<>(List.of(content.split("\n", -1)));
      // Handle file ending with newline: split preserves trailing empty string - we will reconstruct
      // But Files.readString of file that ends with newline will split correctly
      // If original file was empty, split gives [""] -> we want []
      if (originalLines.size() == 1 && originalLines.get(0).isEmpty() && !Files.readString(target, StandardCharsets.UTF_8).isEmpty()) {
        // keep as is
      } else if (originalLines.size() == 1 && originalLines.get(0).isEmpty() && Files.size(target) == 0) {
        originalLines = new ArrayList<>();
      }
      // If file ends without newline, last element is last line without newline; we handle later
    } else {
      // Creating new file
      Files.createDirectories(target.getParent());
      originalLines = new ArrayList<>();
    }

    List<String> newLines = new ArrayList<>();
    int origIdx = 0;
    for (Hunk hunk : filePatch.hunks) {
      Matcher m = HUNK_HEADER.matcher(hunk.header);
      if (!m.matches()) continue;
      int oldStart = Integer.parseInt(m.group(1));
      int oldCount = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
      // oldStart is 1-indexed; convert to 0-indexed
      int hunkOrigStart = Math.max(0, oldStart - 1);
      // Copy context before hunk
      while (origIdx < hunkOrigStart) {
        if (origIdx < originalLines.size()) {
          newLines.add(originalLines.get(origIdx));
        }
        origIdx++;
      }
      // Apply hunk lines
      for (String line : hunk.lines) {
        if (line.startsWith(" ")) {
          // Context
          if (origIdx >= originalLines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hunk context beyond file end for " + filePatch.relativePath);
          }
          String expected = line.substring(1);
          String actual = originalLines.get(origIdx);
          if (!expected.equals(actual)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hunk context mismatch at " + filePatch.relativePath + ":" + (origIdx + 1));
          }
          newLines.add(actual);
          origIdx++;
        } else if (line.startsWith("-")) {
          String expected = line.substring(1);
          if (origIdx >= originalLines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hunk deletion beyond file end for " + filePatch.relativePath);
          }
          String actual = originalLines.get(origIdx);
          if (!expected.equals(actual)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hunk deletion mismatch at " + filePatch.relativePath);
          }
          origIdx++;
        } else if (line.startsWith("+")) {
          newLines.add(line.substring(1));
        } else if (line.startsWith("\\")) {
          // "\ No newline at end of file" - ignore
        } else if (line.isEmpty()) {
          // Should not happen inside hunk, but treat as context empty line
          if (origIdx < originalLines.size()) {
            newLines.add(originalLines.get(origIdx));
            origIdx++;
          }
        }
      }
    }
    // Copy remaining original lines
    while (origIdx < originalLines.size()) {
      newLines.add(originalLines.get(origIdx));
      origIdx++;
    }

    // Write atomically: write to temp then move
    Path tempFile = Files.createTempFile(target.getParent(), "patch-", ".tmp");
    try {
      String newContent = String.join("\n", newLines);
      // Preserve trailing newline if original had it or if new content should end with newline
      // Simple: if diff hunks indicate file should end with newline, we ensure it
      // For now, ensure we don't add extra newline if original was empty and we created file
      Files.writeString(tempFile, newContent, StandardCharsets.UTF_8);
      Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  private void copyDirectory(Path source, Path target) throws IOException {
    Files.createDirectories(target);
    try (var stream = Files.walk(source)) {
      for (Path src : (Iterable<Path>) stream::iterator) {
        Path dest = target.resolve(source.relativize(src).toString());
        if (Files.isDirectory(src)) {
          Files.createDirectories(dest);
        } else if (Files.isRegularFile(src)) {
          Files.createDirectories(dest.getParent());
          Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private void restoreBackup(Path projectDir, Path backupSnapshot) throws IOException {
    // Delete current projectDir and restore from backup
    storage.deleteQuietly(projectDir);
    copyDirectory(backupSnapshot, projectDir);
  }

  private record FilePatch(String relativePath, List<Hunk> hunks) {}
  private static class Hunk {
    final String header;
    final List<String> lines = new ArrayList<>();
    Hunk(String header) { this.header = header; }
  }
}
