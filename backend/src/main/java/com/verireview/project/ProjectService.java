package com.verireview.project;

import com.verireview.audit.AuditService;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.ingestion.ZipIngestionService;
import com.verireview.project.dto.CreateProjectRequest;
import com.verireview.project.dto.FileContentResponse;
import com.verireview.common.PagedResponse;
import com.verireview.project.dto.ProjectFileResponse;
import com.verireview.project.dto.ProjectResponse;
import com.verireview.project.dto.UpdateProjectRequest;
import com.verireview.user.UserRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Project workflows behind the auth boundary. Every lookup is owner-scoped:
 * foreign or soft-deleted projects answer 404 so callers cannot enumerate
 * other users' projects (SECURITY_DESIGN T6, AGENTS.md rule 18).
 */
@Service
public class ProjectService {

  private final ProjectRepository projects;
  private final ProjectFileRepository files;
  private final UserRepository users;
  private final ZipIngestionService zipIngestion;
  private final ProjectStorage storage;
  private final AuditService audits;

  public ProjectService(
      ProjectRepository projects,
      ProjectFileRepository files,
      UserRepository users,
      ZipIngestionService zipIngestion,
      ProjectStorage storage,
      AuditService audits) {
    this.projects = projects;
    this.files = files;
    this.users = users;
    this.zipIngestion = zipIngestion;
    this.storage = storage;
    this.audits = audits;
  }

  @Transactional
  public ProjectResponse createShell(UUID ownerId, CreateProjectRequest request) {
    String name = request.name().trim();
    if (projects.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
    }
    Project project = new Project(users.getReferenceById(ownerId), name,
        request.sourceType() == null ? ProjectSourceType.ZIP_UPLOAD : request.sourceType());
    project.setDescription(request.description());
    project.setLanguage(request.language());
    try {
      projects.saveAndFlush(project);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
    }
    audits.record(project.getOwner(), "PROJECT_CREATED", "project", project.getId().toString());
    return toResponse(project, 0);
  }

  @Transactional
  public ProjectResponse importZip(
      UUID ownerId, String name, String description, String language, MultipartFile file) {
    ZipIngestionService.ImportedProject imported =
        zipIngestion.ingest(ownerId, name, description, language, file);
    return toResponse(imported.project(), imported.fileCount());
  }

  @Transactional(readOnly = true)
  public PagedResponse<ProjectResponse> list(
      UUID ownerId, String search, ProjectSourceType sourceType, Pageable pageable) {
    // Patterns are never null: Hibernate/PostgreSQL cannot bind a null
    // parameter inside CONCAT/LIKE (it becomes bytea), so blanks become '%'.
    Page<Project> page = projects.search(ownerId, containsPattern(search), sourceType, pageable);
    Page<ProjectResponse> mapped = page.map(project ->
        toResponse(project, files.countByProjectId(project.getId())));
    return PagedResponse.of(mapped);
  }

  @Transactional(readOnly = true)
  public ProjectResponse get(UUID ownerId, UUID projectId) {
    Project project = owned(ownerId, projectId);
    return toResponse(project, files.countByProjectId(project.getId()));
  }

  @Transactional
  public ProjectResponse update(UUID ownerId, UUID projectId, UpdateProjectRequest request) {
    Project project = owned(ownerId, projectId);
    if (request.name() != null) {
      String name = request.name().trim();
      if (name.isEmpty() || name.length() > 200) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project name");
      }
      if (!name.equals(project.getName())
          && projects.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, name)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Project name is already used");
      }
      project.setName(name);
    }
    if (request.description() != null) {
      if (request.description().length() > 5000) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description is too long");
      }
      project.setDescription(request.description().isBlank() ? null : request.description());
    }
    audits.record(project.getOwner(), "PROJECT_UPDATED", "project", project.getId().toString());
    return toResponse(project, files.countByProjectId(project.getId()));
  }

  @Transactional
  public void delete(UUID ownerId, UUID projectId) {
    Project project = owned(ownerId, projectId);
    project.setDeletedAt(Instant.now());
    project.setStatus(ProjectStatus.ARCHIVED);
    audits.record(project.getOwner(), "PROJECT_DELETED", "project", project.getId().toString());
  }

  @Transactional(readOnly = true)
  public PagedResponse<ProjectFileResponse> listFiles(
      UUID ownerId, UUID projectId, String pathPrefix, String search, Pageable pageable) {
    Project project = owned(ownerId, projectId);
    String prefix = pathPrefix == null || pathPrefix.isBlank() ? "" : pathPrefix.trim();
    Page<ProjectFile> page = files.search(project.getId(), prefix, containsPattern(search),
        pageable);
    return PagedResponse.of(page.map(file -> new ProjectFileResponse(
        file.getId(), file.getPath(), file.getLanguage(), file.getSizeBytes(), file.getSha256())));
  }

  @Transactional(readOnly = true)
  public FileContentResponse readFile(UUID ownerId, UUID projectId, String path) {
    Project project = owned(ownerId, projectId);
    if (project.getStorageRef() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
    }
    Path projectDir = Path.of(project.getStorageRef());
    try {
      ProjectStorage.ReadResult read = storage.readCapped(projectDir, path);
      return new FileContentResponse(path, read.sizeBytes(), read.truncated(), read.content());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the file");
    }
  }

  private Project owned(UUID ownerId, UUID projectId) {
    return projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** LIKE pattern with wildcard characters escaped (queries use ESCAPE '\'). */
  private static String containsPattern(String value) {
    if (value == null || value.isBlank()) {
      return "%";
    }
    String escaped = value.trim()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");
    return "%" + escaped + "%";
  }

  /** Manual mapping (see ADR-003 note in UserService). Storage paths stay inside. */
  static ProjectResponse toResponse(Project project, long fileCount) {
    return new ProjectResponse(
        project.getId(),
        project.getName(),
        project.getDescription(),
        project.getSourceType(),
        project.getLanguage(),
        project.getStatus(),
        fileCount,
        project.getCreatedAt(),
        project.getUpdatedAt());
  }
}
