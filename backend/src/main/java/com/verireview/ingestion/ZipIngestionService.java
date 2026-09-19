package com.verireview.ingestion;

import com.verireview.audit.AuditService;
import com.verireview.project.Project;
import com.verireview.project.ProjectFile;
import com.verireview.project.ProjectFileRepository;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * ZIP intake pipeline (SECURITY_DESIGN §3, API_DESIGN §4): extension + magic
 * bytes → entry scan (count, declared sizes, traversal/symlink rejection) →
 * streaming extract to quarantine with per-file and total uncompressed caps →
 * hash + inventory → move to the project store. Any failure deletes the
 * quarantine. Uploaded code is stored, never executed.
 */
@Service
public class ZipIngestionService {

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

  public ZipIngestionService(
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

  public record ImportedProject(Project project, int fileCount) {
  }

  @Transactional
  public ImportedProject ingest(
      UUID ownerId, String name, String description, String language, MultipartFile upload) {
    String projectName = validateName(name);
    Path staged = stageUpload(upload);
    Path quarantine;
    try {
      quarantine = storage.quarantineDir();
    } catch (IOException e) {
      deleteQuietly(staged);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not stage the upload");
    }
    boolean moved = false;
    try {
      List<ExtractedFile> extracted = extract(quarantine, staged);
      if (extracted.isEmpty()) {
        throw badRequest("Archive contains no files");
      }
      if (projects.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, projectName)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
      }
      Project project = new Project(users.getReferenceById(ownerId), projectName,
          ProjectSourceType.ZIP_UPLOAD);
      project.setDescription(blankToNull(description));
      project.setLanguage(blankToNull(language));
      try {
        projects.saveAndFlush(project);
      } catch (DataIntegrityViolationException e) {
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
      audits.record(project.getOwner(), "PROJECT_ZIP_IMPORTED", "project",
          project.getId().toString());
      return new ImportedProject(project, rows.size());
    } finally {
      deleteQuietly(staged);
      if (!moved) {
        storage.deleteQuietly(quarantine);
      }
    }
  }

  private Path stageUpload(MultipartFile upload) {
    if (upload == null || upload.isEmpty()) {
      throw badRequest("A non-empty .zip file is required");
    }
    if (upload.getSize() > limits.maxZipBytes()) {
      throw badRequest("Archive exceeds the 50 MB project limit");
    }
    String filename = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename();
    if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
      throw badRequest("Only .zip uploads are accepted");
    }
    try {
      Path staged = Files.createTempFile("verireview-upload-", ".zip");
      upload.transferTo(staged);
      assertZipMagic(staged);
      return staged;
    } catch (IOException e) {
      throw badRequest("Could not read the uploaded archive");
    }
  }

  private static void assertZipMagic(Path staged) throws IOException {
    try (InputStream in = Files.newInputStream(staged)) {
      byte[] magic = in.readNBytes(4);
      if (magic.length < 4 || magic[0] != 'P' || magic[1] != 'K') {
        throw badRequest("File is not a ZIP archive");
      }
    }
  }

  private List<ExtractedFile> extract(Path quarantine, Path staged) {
    List<ExtractedFile> extracted = new ArrayList<>();
    long totalUncompressed = 0;
    try (ZipFile zip = new ZipFile(staged.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String relative = sanitizeEntryName(entry.getName());
        if (relative == null) {
          continue; // directory, macOS metadata, or DS_Store
        }
        if (extracted.size() >= limits.maxFiles()) {
          throw badRequest("Archive exceeds the 2000 file limit");
        }
        Path target = storage.resolveJailed(quarantine, relative);
        totalUncompressed = copyCapped(zip, entry, target, totalUncompressed);
        long size;
        try {
          size = Files.size(target);
        } catch (IOException e) {
          throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR, "Could not extract the archive");
        }
        extracted.add(new ExtractedFile(relative, size, sha256Hex(target), languageOf(relative)));
      }
    } catch (IOException e) {
      if (e instanceof java.util.zip.ZipException) {
        throw badRequest("File is not a readable ZIP archive");
      }
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not extract the archive");
    }
    return extracted;
  }

  /**
   * Returns the jail-relative posix path, or null for entries to skip
   * (directories, macOS metadata). Throws 400 on absolute paths, drive
   * letters, or any {@code ..} component. The authoritative jail check runs
   * again at extraction time via {@code ProjectStorage.resolveJailed}.
   */
  private String sanitizeEntryName(String raw) {
    if (raw == null) {
      return null;
    }
    String unified = raw.replace('\\', '/');
    if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
      throw badRequest("Archive entry escapes the project: " + raw);
    }
    if (unified.endsWith("/")) {
      return null;
    }
    if (unified.startsWith("__MACOSX/") || unified.endsWith(".DS_Store")) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (String part : unified.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        throw badRequest("Archive entry escapes the project: " + raw);
      }
      parts.add(part);
    }
    if (parts.isEmpty()) {
      return null;
    }
    return String.join("/", parts);
  }

  private long copyCapped(ZipFile zip, ZipEntry entry, Path target, long totalSoFar)
      throws IOException {
    try {
      Files.createDirectories(target.getParent());
    } catch (IOException e) {
      throw badRequest("Archive entry escapes the project: " + entry.getName());
    }
    long fileBytes = 0;
    try (InputStream in = zip.getInputStream(entry);
        OutputStream out = Files.newOutputStream(target)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        fileBytes += read;
        if (fileBytes > limits.maxSingleFileBytes()) {
          throw badRequest("Archive contains an oversized file: " + entry.getName());
        }
        if (totalSoFar + fileBytes > limits.maxTotalUncompressedBytes()) {
          throw badRequest("Archive exceeds the 200 MB uncompressed limit");
        }
        out.write(buffer, 0, read);
      }
    } catch (ResponseStatusException e) {
      deleteQuietly(target);
      throw e;
    } catch (IOException e) {
      deleteQuietly(target);
      throw e;
    }
    if (Files.isSymbolicLink(target)) {
      deleteQuietly(target);
      throw badRequest("Archive contains a symlink: " + entry.getName());
    }
    return totalSoFar + fileBytes;
  }

  private static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file);
          DigestInputStream digested = new DigestInputStream(in, digest)) {
        digested.readAllBytes();
      }
      return HexFormat.of().formatHex(digest.digest());
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

  private record ExtractedFile(String relativePath, long sizeBytes, String sha256, String language) {
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

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best effort cleanup.
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
