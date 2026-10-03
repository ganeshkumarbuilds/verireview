package com.verireview.ingestion;

import com.verireview.audit.AuditService;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.user.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasteIngestionService {

  private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
      Map.entry("java", "java"), Map.entry("py", "python"), Map.entry("js", "javascript"),
      Map.entry("jsx", "javascript"), Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
      Map.entry("kt", "kotlin"), Map.entry("go", "go"), Map.entry("rs", "rust"),
      Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("cpp", "cpp"), Map.entry("hpp", "cpp"),
      Map.entry("cs", "csharp"), Map.entry("rb", "ruby"), Map.entry("php", "php"),
      Map.entry("swift", "swift"), Map.entry("scala", "scala"), Map.entry("sql", "sql"),
      Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("css", "css"),
      Map.entry("json", "json"), Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"),
      Map.entry("md", "markdown"), Map.entry("txt", "text"), Map.entry("sh", "shell"),
      Map.entry("gradle", "gradle"), Map.entry("properties", "properties"));

  private final IngestionLimits limits;
  private final ProjectStorage storage;
  private final ProjectRepository projects;
  private final ProjectFileRepository files;
  private final UserRepository users;
  private final AuditService audits;

  public PasteIngestionService(
      IngestionLimits limits,
      ProjectStorage storage,
      ProjectRepository projects,
      ProjectFileRepository files,
      UserRepository users,
      AuditService audits) {
    this.limits = limits;
    this.storage = storage;
    this.projects = projects;
    this.files = files;
    this.users = users;
    this.audits = audits;
  }

  public record PasteFileInput(String path, String content) {
  }

  public record ImportedProject(Project project, int fileCount) {
  }

  @Transactional
  public ImportedProject ingest(
      UUID ownerId, String name, String description, String language, List<PasteFileInput> pasteFiles) {
    String projectName = validateName(name);
    if (pasteFiles == null || pasteFiles.isEmpty()) {
      throw badRequest("At least one file is required");
    }
    if (pasteFiles.size() > limits.maxFiles()) {
      throw badRequest("Paste intake exceeds the " + limits.maxFiles() + " file limit");
    }
    Path quarantine;
    try {
      quarantine = storage.quarantineDir();
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not stage the import");
    }
    boolean moved = false;
    try {
      List<ExtractedFile> extracted = writePasteFiles(quarantine, pasteFiles);
      if (extracted.isEmpty()) {
        throw badRequest("No valid files provided");
      }
      if (projects.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, projectName)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
      }
      Project project = new Project(users.getReferenceById(ownerId), projectName,
          ProjectSourceType.PASTE);
      project.setDescription(blankToNull(description));
      project.setLanguage(blankToNull(language));
      try {
        projects.saveAndFlush(project);
      } catch (org.springframework.dao.DataIntegrityViolationException e) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
      }
      Path projectDir;
      try {
        projectDir = storage.moveToProject(quarantine, project.getId());
        moved = true;
      } catch (IOException e) {
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Could not store the project");
      }
      project.setStorageRef(projectDir.toString());
      List<ProjectFile> rows = new ArrayList<>(extracted.size());
      for (ExtractedFile entry : extracted) {
        ProjectFile row = new ProjectFile(project, entry.relativePath(), entry.sizeBytes(),
            entry.sha256());
        row.setLanguage(entry.language());
        row.setContentRef(entry.relativePath());
        rows.add(row);
      }
      files.saveAll(rows);
      audits.record(project.getOwner(), "PROJECT_PASTE_IMPORTED", "project",
          project.getId().toString(), String.format("{\"fileCount\":%d}", extracted.size()));
      return new ImportedProject(project, rows.size());
    } finally {
      if (!moved) {
        storage.deleteQuietly(quarantine);
      }
    }
  }

  private List<ExtractedFile> writePasteFiles(Path quarantine, List<PasteFileInput> pasteFiles) {
    List<ExtractedFile> extracted = new ArrayList<>();
    long totalUncompressed = 0;
    for (PasteFileInput input : pasteFiles) {
      String relativePath = sanitizePath(input.path());
      if (relativePath == null) {
        throw badRequest("Invalid file path: " + input.path());
      }
      Path target = storage.resolveJailed(quarantine, relativePath);
      try {
        Files.createDirectories(target.getParent());
      } catch (IOException e) {
        throw badRequest("File path escapes the project: " + input.path());
      }
      byte[] contentBytes = input.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      long size = contentBytes.length;
      if (size > limits.maxSingleFileBytes()) {
        throw badRequest("File exceeds the " + (limits.maxSingleFileBytes() / 1024 / 1024) + " MB size limit: " + relativePath);
      }
      totalUncompressed += size;
      if (totalUncompressed > limits.maxTotalUncompressedBytes()) {
        throw badRequest("Total paste size exceeds the " + (limits.maxTotalUncompressedBytes() / 1024 / 1024) + " MB limit");
      }
      try {
        Files.write(target, contentBytes);
      } catch (IOException e) {
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Could not write file: " + relativePath);
      }
      String sha256 = sha256Hex(contentBytes);
      String language = languageOf(relativePath);
      extracted.add(new ExtractedFile(relativePath, size, sha256, language));
    }
    return extracted;
  }

  /**
   * Validates and normalizes a user-supplied file path. Rejects absolute paths,
   * drive letters, and any {@code ..} components. Returns null for empty/invalid paths.
   */
  private String sanitizePath(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String unified = raw.replace('\\', '/');
    if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
      throw badRequest("File path escapes the project: " + raw);
    }
    List<String> parts = new ArrayList<>();
    for (String part : unified.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        throw badRequest("File path escapes the project: " + raw);
      }
      parts.add(part);
    }
    if (parts.isEmpty()) {
      return null;
    }
    return String.join("/", parts);
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(content);
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (java.io.InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  static String languageOf(String relativePath) {
    int dot = relativePath.lastIndexOf('.');
    if (dot < 0 || dot == relativePath.length() - 1) {
      return null;
    }
    return LANGUAGE_BY_EXTENSION.get(
        relativePath.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  private static String validateName(String name) {
    if (name == null || name.isBlank() || name.trim().length() > 200) {
      throw badRequest("Project name must be 1-200 characters");
    }
    return name.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record ExtractedFile(String relativePath, long sizeBytes, String sha256, String language) {
  }
}