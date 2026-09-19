package com.verireview.execution;

import com.verireview.audit.AuditService;
import com.verireview.execution.dto.ExecutionRunResponse;
import com.verireview.fix.Patch;
import com.verireview.fix.PatchRepository;
import com.verireview.fix.PatchStatus;
import com.verireview.ingestion.ProjectStorage;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.verification.BuildStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionService {

  private final ExecutionRunRepository executions;
  private final ProjectRepository projects;
  private final PatchRepository patches;
  private final ProjectStorage storage;
  private final SandboxRunner sandbox;
  private final AuditService audits;

  public ExecutionService(
      ExecutionRunRepository executions,
      ProjectRepository projects,
      PatchRepository patches,
      ProjectStorage storage,
      SandboxRunner sandbox,
      AuditService audits) {
    this.executions = executions;
    this.projects = projects;
    this.patches = patches;
    this.storage = storage;
    this.sandbox = sandbox;
    this.audits = audits;
  }

  @Transactional
  public ExecutionRunResponse execute(UUID ownerId, UUID projectId, UUID patchId) {
    Project project = projects.findById(projectId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }

    Patch patch = null;
    if (patchId != null) {
      patch = patches.findById(patchId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found"));
      // Ensure patch belongs to this project via FixRequest->Finding->Review->Project or direct project link
      UUID patchProjectId = patch.getProject() != null ? patch.getProject().getId()
          : patch.getFixRequest().getFinding().getReview().getProject().getId();
      if (!patchProjectId.equals(projectId)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patch does not belong to project");
      }
      if (patch.getStatus() != PatchStatus.APPLIED) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patch must be APPLIED to execute");
      }
    } else {
      // Find latest APPLIED patch for project if not specified
      patch = patches.findAll().stream()
          .filter(p -> p.getStatus() == PatchStatus.APPLIED)
          .filter(p -> {
            UUID pid = p.getProject() != null ? p.getProject().getId()
                : p.getFixRequest().getFinding().getReview().getProject().getId();
            return pid.equals(projectId);
          })
          .findFirst()
          .orElse(null);
      if (patch == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No APPLIED patch found for project");
      }
    }

    Path projectDir = storage.projectDir(project.getId());
    if (!java.nio.file.Files.isDirectory(projectDir)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project storage not found");
    }

    ExecutionRun run = new ExecutionRun(project, patch);
    run.setStatus(ExecutionStatus.PENDING);
    run.setBuildStatus(BuildStatus.PENDING);
    executions.save(run);
    audits.record(project.getOwner(), "EXECUTION_STARTED", "execution_run", run.getId().toString());

    // Transition to RUNNING
    run.setStatus(ExecutionStatus.RUNNING);
    run.setBuildStatus(BuildStatus.RUNNING);
    executions.save(run);

    Instant start = Instant.now();
    SandboxRunner.ExecutionResult result;
    try {
      result = sandbox.executeBuild(projectDir);
    } catch (SandboxRunner.SandboxException e) {
      run.setStatus(ExecutionStatus.FAILURE);
      run.setBuildStatus(BuildStatus.FAILURE);
      run.setStderr(truncate(e.getMessage(), 65536));
      run.setDurationMs(java.time.Duration.between(start, Instant.now()).toMillis());
      executions.save(run);
      audits.record(project.getOwner(), "EXECUTION_FAILED", "execution_run", run.getId().toString());
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Sandbox execution failed: " + e.getMessage());
    }

    // Persist evidence
    run.setExitCode(result.exitCode());
    run.setStdout(truncate(result.stdout(), 1048576));
    run.setStderr(truncate(result.stderr(), 1048576));
    run.setDurationMs(result.durationMs());
    if (result.timedOut()) {
      run.setStatus(ExecutionStatus.TIMEOUT);
      run.setBuildStatus(BuildStatus.TIMEOUT);
    } else if (result.exitCode() == 0) {
      run.setStatus(ExecutionStatus.SUCCESS);
      run.setBuildStatus(BuildStatus.SUCCESS);
    } else {
      run.setStatus(ExecutionStatus.FAILURE);
      run.setBuildStatus(BuildStatus.FAILURE);
    }
    executions.save(run);
    audits.record(project.getOwner(),
        result.timedOut() ? "EXECUTION_TIMEOUT" : (result.exitCode() == 0 ? "EXECUTION_COMPLETED" : "EXECUTION_FAILED"),
        "execution_run", run.getId().toString());

    return toResponse(run);
  }

  @Transactional(readOnly = true)
  public ExecutionRunResponse getRun(UUID ownerId, UUID executionId) {
    ExecutionRun run = executions.findById(executionId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));
    Project project = run.getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
    }
    return toResponse(run);
  }

  @Transactional(readOnly = true)
  public List<ExecutionRunResponse> listRuns(UUID ownerId, UUID projectId) {
    Project project = projects.findById(projectId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }
    return executions.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
        .map(ExecutionService::toResponse)
        .toList();
  }

  private static String truncate(String s, int max) {
    if (s == null) return null;
    if (s.length() <= max) return s;
    return s.substring(0, max) + "\n...[truncated at " + max + " bytes]";
  }

  static ExecutionRunResponse toResponse(ExecutionRun run) {
    return new ExecutionRunResponse(
        run.getId(),
        run.getProject().getId(),
        run.getPatch() != null ? run.getPatch().getId() : null,
        run.getStatus(),
        run.getExitCode(),
        run.getStdout(),
        run.getStderr(),
        run.getDurationMs(),
        run.getBuildStatus(),
        run.getCreatedAt(),
        run.getUpdatedAt());
  }
}
