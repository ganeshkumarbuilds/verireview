package com.verireview.project;

import com.verireview.common.PagedResponse;
import com.verireview.ingestion.IngestionLimits;
import com.verireview.ingestion.PasteIngestionService;
import com.verireview.project.dto.CreateProjectRequest;
import com.verireview.project.dto.FileContentResponse;
import com.verireview.project.dto.GitHubImportRequest;
import com.verireview.project.dto.ImportJobCreatedResponse;
import com.verireview.project.dto.ImportJobResponse;
import com.verireview.project.dto.ImportLimitsResponse;
import com.verireview.project.dto.PasteImportRequest;
import com.verireview.project.dto.ProjectFileResponse;
import com.verireview.project.dto.ProjectResponse;
import com.verireview.project.dto.UpdateProjectRequest;
import com.verireview.security.VeriReviewUserDetails;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Project intake boundary (API_DESIGN §2, Phase 5 scope): shells, ZIP import,
 * file listing, and capped file views. Thin by rule — validation, ownership,
 * and extraction live in the service layer. GitHub import is Phase 12.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

  private final ProjectService projects;
  private final com.verireview.generation.GenerationService generations;
  private final ImportJobService importJobs;
  private final IngestionLimits limits;

  public ProjectController(
      ProjectService projects,
      com.verireview.generation.GenerationService generations,
      ImportJobService importJobs,
      IngestionLimits limits) {
    this.projects = projects;
    this.generations = generations;
    this.importJobs = importJobs;
    this.limits = limits;
  }

  @PostMapping
  public ResponseEntity<ProjectResponse> create(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody CreateProjectRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(projects.createShell(principal.getId(), request));
  }

  @GetMapping
  public ResponseEntity<PagedResponse<ProjectResponse>> list(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @RequestParam(value = "search", required = false) String search,
      @RequestParam(value = "sourceType", required = false) ProjectSourceType sourceType,
      @PageableDefault(size = 20, sort = "createdAt",
          direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
    return ResponseEntity.ok(projects.list(principal.getId(), search, sourceType, pageable));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ProjectResponse> get(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(projects.get(principal.getId(), id));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ProjectResponse> update(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @Valid @RequestBody UpdateProjectRequest request) {
    return ResponseEntity.ok(projects.update(principal.getId(), id, request));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    projects.delete(principal.getId(), id);
    return ResponseEntity.noContent().build();
  }

  /**
   * ZIP intake (async). Caps (1 GB archive, 50000 files, 4 GB uncompressed, 10 MB
   * per file) are enforced in {@code ZipIngestionService}; larger multipart
   * bodies are rejected at 413 before reaching this method.
   * Returns 202 with jobId; poll GET /import/jobs/{id} for progress.
   */
  @PostMapping(path = "/import/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ImportJobCreatedResponse> importZip(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @RequestParam("file") MultipartFile file,
      @RequestParam("name") String name,
      @RequestParam(value = "description", required = false) String description,
      @RequestParam(value = "language", required = false) String language) {
    ImportJob job = importJobs.createZipJob(principal.getId(), name, description, language, file);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ImportJobCreatedResponse.from(job));
  }

  /**
   * ZIP intake (synchronous, for backward compatibility and testing).
   * Caps (1 GB archive, 50000 files, 4 GB uncompressed, 10 MB
   * per file) are enforced in {@code ZipIngestionService}.
   * Returns 201 with project on success, or appropriate error status.
   */
  @PostMapping(path = "/import/zip/sync", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ProjectResponse> importZipSync(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @RequestParam("file") MultipartFile file,
      @RequestParam("name") String name,
      @RequestParam(value = "description", required = false) String description,
      @RequestParam(value = "language", required = false) String language) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(projects.importZip(principal.getId(), name, description, language, file));
  }

  /**
   * GitHub shallow-clone import (async). Public GitHub URLs only, allowlisted
   * to github.com. Clones with depth=1, extracts files to quarantine, then
   * stages to project storage. Returns 202 with jobId; poll GET /import/jobs/{id} for progress.
   * Limits: 50000 files, 4 GB total, 10 MB per file.
   */
  @PostMapping(path = "/import/github", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ImportJobCreatedResponse> importGitHub(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody GitHubImportRequest request) {
    ImportJob job = importJobs.createGitHubJob(
        principal.getId(), request.name(), request.description(), request.language(), request.url());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ImportJobCreatedResponse.from(job));
  }

  /**
   * GitHub shallow-clone import (synchronous, for backward compatibility and testing).
   * Limits: 50000 files, 4 GB total, 10 MB per file.
   */
  @PostMapping(path = "/import/github/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProjectResponse> importGitHubSync(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody GitHubImportRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(projects.importGitHub(
            principal.getId(), request.name(), request.description(), request.language(), request.url()));
  }

  /**
   * Paste-file intake (Phase 5). Accepts a list of file paths + contents (max 10 MB
   * each, 50000 files total, 4 GB aggregate). Files are written to quarantine,
   * validated, then staged to project storage.
   */
  @PostMapping(path = "/import/paste", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProjectResponse> importPaste(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody PasteImportRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(projects.importPaste(
            principal.getId(),
            request.name(),
            request.description(),
            request.language(),
            request.files()));
  }

  /**
   * Returns the current import limits for ZIP and GitHub imports.
   * No authentication required — public endpoint for UI pre-validation.
   */
  @GetMapping("/import/limits")
  public ResponseEntity<ImportLimitsResponse> importLimits() {
    long maxZip = limits.maxZipBytes();
    long maxUncompressed = limits.maxTotalUncompressedBytes();
    long maxSingle = limits.maxSingleFileBytes();
    return ResponseEntity.ok(new ImportLimitsResponse(
        maxZip,
        limits.maxFiles(),
        maxUncompressed,
        maxSingle,
        formatBytes(maxZip),
        formatBytes(maxUncompressed),
        formatBytes(maxSingle)));
  }

  /**
   * Get import job status by ID. Owner-scoped.
   */
  @GetMapping("/import/jobs/{id}")
  public ResponseEntity<ImportJobResponse> getImportJob(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    ImportJob job = importJobs.findByIdAndOwner(id, principal.getId())
        .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
            HttpStatus.NOT_FOUND, "Import job not found"));
    return ResponseEntity.ok(ImportJobResponse.from(job));
  }

  /**
   * List import jobs for the current user. Owner-scoped.
   */
  @GetMapping("/import/jobs")
  public ResponseEntity<PagedResponse<ImportJobResponse>> listImportJobs(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PageableDefault(size = 20, sort = "createdAt",
          direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
    return ResponseEntity.ok(
        PagedResponse.of(importJobs.findByOwner(principal.getId(), pageable).map(ImportJobResponse::from)));
  }

  /**
   * Cancel a queued import job. Only QUEUED jobs can be cancelled.
   */
  @DeleteMapping("/import/jobs/{id}")
  public ResponseEntity<Void> cancelImportJob(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    importJobs.cancelJob(id, principal.getId());
    return ResponseEntity.noContent().build();
  }

  private static String formatBytes(long bytes) {
    if (bytes >= 1024L * 1024L * 1024L) {
      return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    } else if (bytes >= 1024L * 1024L) {
      return String.format("%.0f MB", bytes / (1024.0 * 1024.0));
    } else if (bytes >= 1024L) {
      return String.format("%.0f KB", bytes / 1024.0);
    }
    return bytes + " bytes";
  }

  @GetMapping("/{id}/files")
  public ResponseEntity<PagedResponse<ProjectFileResponse>> files(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @RequestParam(value = "pathPrefix", required = false) String pathPrefix,
      @RequestParam(value = "search", required = false) String search,
      @PageableDefault(size = 50, sort = "path",
          direction = org.springframework.data.domain.Sort.Direction.ASC) Pageable pageable) {
    return ResponseEntity.ok(
        projects.listFiles(principal.getId(), id, pathPrefix, search, pageable));
  }

  @GetMapping("/{id}/files/content")
  public ResponseEntity<FileContentResponse> fileContent(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id,
      @RequestParam("path") String path) {
    return ResponseEntity.ok(projects.readFile(principal.getId(), id, path));
  }

  /**
   * Latest generation linked to a project, for the generated-project
   * workspace panel. Owner-checked through the project (404 otherwise);
   * 404 when the project has no generation yet.
   */
  @GetMapping("/{id}/generation")
  public ResponseEntity<com.verireview.generation.dto.GenerationResponse> generation(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID id) {
    return ResponseEntity.ok(generations.forProject(principal.getId(), id));
  }
}
