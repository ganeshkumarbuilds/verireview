package com.verireview.project;

import com.verireview.project.dto.CreateProjectRequest;
import com.verireview.project.dto.FileContentResponse;
import com.verireview.common.PagedResponse;
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

  public ProjectController(
      ProjectService projects,
      com.verireview.generation.GenerationService generations) {
    this.projects = projects;
    this.generations = generations;
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
   * ZIP intake. Caps (50 MB archive, 2000 files, 200 MB uncompressed, 10 MB
   * per file) are enforced in {@code ZipIngestionService}; larger multipart
   * bodies are rejected at 413 before reaching this method.
   */
  @PostMapping(path = "/import/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ProjectResponse> importZip(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @RequestParam("file") MultipartFile file,
      @RequestParam("name") String name,
      @RequestParam(value = "description", required = false) String description,
      @RequestParam(value = "language", required = false) String language) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(projects.importZip(principal.getId(), name, description, language, file));
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
