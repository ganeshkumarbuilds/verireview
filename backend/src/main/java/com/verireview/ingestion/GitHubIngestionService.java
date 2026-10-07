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
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GitHubIngestionService {

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

  private static final List<String> ALLOWED_HOSTS = List.of(
      "github.com",
      "raw.githubusercontent.com");

  private final IngestionLimits limits;
  private final ProjectStorage storage;
  private final ProjectRepository projects;
  private final ProjectFileRepository files;
  private final UserRepository users;
  private final AuditService audits;

  public GitHubIngestionService(
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

  public record ImportedProject(Project project, int fileCount, String commitSha) {
  }

  @Transactional
  public ImportedProject ingest(
      UUID ownerId, String name, String description, String language, String url) {
    String projectName = validateName(name);
    String validatedUrl = validateAndNormalizeUrl(url);
    Path quarantine;
    try {
      quarantine = storage.quarantineDir();
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not stage the import");
    }
    boolean moved = false;
    try {
      String commitSha = shallowClone(quarantine, validatedUrl);
      List<ExtractedFile> extracted = scanQuarantine(quarantine, validatedUrl);
      if (projects.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, projectName)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
      }
      Project project = new Project(users.getReferenceById(ownerId), projectName,
          ProjectSourceType.GITHUB);
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
      audits.record(project.getOwner(), "PROJECT_GITHUB_IMPORTED", "project",
          project.getId().toString(), String.format("{\"url\":\"%s\",\"commitSha\":\"%s\"}", validatedUrl, commitSha));
      return new ImportedProject(project, rows.size(), commitSha);
    } finally {
      if (!moved) {
        storage.deleteQuietly(quarantine);
      }
    }
  }

  private String validateAndNormalizeUrl(String url) {
    if (url == null || url.isBlank()) {
      throw GitHubImportException.invalidUrl(url, "A GitHub repository URL is required");
    }
    String trimmed = url.trim();
    try {
      java.net.URI uri = new java.net.URI(trimmed);
      String host = uri.getHost();
      if (host == null) {
        throw GitHubImportException.invalidUrl(url, "missing host");
      }
      String lowerHost = host.toLowerCase(Locale.ROOT);
      boolean allowed = ALLOWED_HOSTS.stream().anyMatch(lowerHost::equals);
      if (!allowed) {
        throw GitHubImportException.hostNotAllowed(url);
      }
      String path = uri.getPath();
      if (path == null || path.isBlank() || path.equals("/")) {
        throw GitHubImportException.invalidUrl(url, "missing repository path");
      }
      if (!path.contains("/")) {
        throw GitHubImportException.invalidUrl(url, "must include owner and repository");
      }
      if (trimmed.endsWith(".git")) {
        trimmed = trimmed.substring(0, trimmed.length() - 4);
      }
      return trimmed;
    } catch (java.net.URISyntaxException e) {
      throw GitHubImportException.invalidUrl(url, "Invalid URL format: " + e.getMessage());
    }
  }

  private String shallowClone(Path targetDir, String url) {
    try {
      try (Git git = Git.cloneRepository()
          .setURI(url)
          .setDirectory(targetDir.toFile())
          .setDepth(1)
          .setBranch("main")
          .setTimeout(30)
          .call()) {
        try (Repository repo = git.getRepository();
             RevWalk walk = new RevWalk(repo)) {
          ObjectId head = repo.resolve("HEAD");
          if (head == null) {
            throw GitHubImportException.noCommits(url);
          }
          RevCommit commit = walk.parseCommit(head);
          return commit.getName();
        }
      }
    } catch (GitAPIException e) {
      Throwable cause = e.getCause();
      if (cause instanceof org.eclipse.jgit.errors.MissingObjectException
          || cause instanceof org.eclipse.jgit.errors.NotSupportedException) {
        try {
          try (Git git = Git.cloneRepository()
              .setURI(url)
              .setDirectory(targetDir.toFile())
              .setDepth(1)
              .setBranch("master")
              .setTimeout(30)
              .call()) {
            try (Repository repo = git.getRepository();
                 RevWalk walk = new RevWalk(repo)) {
              ObjectId head = repo.resolve("HEAD");
              if (head == null) {
                throw GitHubImportException.noCommits(url);
              }
              RevCommit commit = walk.parseCommit(head);
              return commit.getName();
            }
          }
        } catch (GitAPIException e2) {
          throw GitHubImportException.cloneFailed(url, e2.getMessage());
        } catch (IOException e2) {
          throw GitHubImportException.cloneFailed(url, e2.getMessage());
        }
      }
      throw GitHubImportException.cloneFailed(url, e.getMessage());
    } catch (IOException e) {
      throw GitHubImportException.cloneFailed(url, e.getMessage());
    }
  }

  private List<ExtractedFile> scanQuarantine(Path quarantine, String url) {
    List<ExtractedFile> extracted = new ArrayList<>();
    try (var stream = Files.walk(quarantine)) {
      List<Path> filePaths = stream
          .filter(Files::isRegularFile)
          .filter(p -> !Files.isSymbolicLink(p))
          .collect(Collectors.toList());
      if (filePaths.size() > limits.maxFiles()) {
        throw GitHubImportException.tooManyFiles(url, filePaths.size(), limits.maxFiles());
      }
      long totalUncompressed = 0;
      for (Path file : filePaths) {
        Path relative = quarantine.relativize(file);
        String relativePath = relative.toString().replace('\\', '/');
        if (relativePath.startsWith(".git/")) {
          continue;
        }
        long size;
        try {
          size = Files.size(file);
        } catch (IOException e) {
          throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file: " + relativePath);
        }
        if (size > limits.maxSingleFileBytes()) {
          throw GitHubImportException.oversizedFile(url, relativePath, size, limits.maxSingleFileBytes());
        }
        totalUncompressed += size;
        if (totalUncompressed > limits.maxTotalUncompressedBytes()) {
          throw GitHubImportException.uncompressedTooLarge(url, totalUncompressed, limits.maxTotalUncompressedBytes());
        }
        String sha256 = sha256Hex(file);
        String language = languageOf(relativePath);
        extracted.add(new ExtractedFile(relativePath, size, sha256, language));
      }
      if (extracted.isEmpty()) {
        throw GitHubImportException.emptyRepository(url);
      }
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not scan repository");
    }
    return extracted;
  }

  private static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
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
      throw new IllegalArgumentException("Project name must be 1-200 characters");
    }
    return name.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record ExtractedFile(String relativePath, long sizeBytes, String sha256, String language) {
  }
}