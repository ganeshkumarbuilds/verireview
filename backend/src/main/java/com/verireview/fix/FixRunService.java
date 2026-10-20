package com.verireview.fix;

import com.verireview.check.CheckFeature;
import com.verireview.check.CheckRun;
import com.verireview.check.CheckRunRepository;
import com.verireview.check.CheckRunService;
import com.verireview.check.CheckStatus;
import com.verireview.check.CheckStep;
import com.verireview.check.CheckStepRepository;
import com.verireview.check.dto.CheckStepResponse;
import com.verireview.common.ErrorMessages;
import com.verireview.execution.ExecutionRun;
import com.verireview.execution.ExecutionRunRepository;
import com.verireview.execution.ExecutionService;
import com.verireview.execution.ExecutionStatus;
import com.verireview.execution.dto.ExecutionRunResponse;
import com.verireview.fix.dto.BulkFixRequestCreate;
import com.verireview.fix.dto.BulkFixRequestCreated;
import com.verireview.fix.dto.CreateFixRequestRequest;
import com.verireview.fix.dto.FixRunChangedFileResponse;
import com.verireview.fix.dto.FixRunDetailResponse;
import com.verireview.fix.dto.FixRunSummaryResponse;
import com.verireview.fix.dto.PatchResponse;
import com.verireview.fix.dto.StartFixRunRequest;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.review.Finding;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingStatus;
import com.verireview.verification.VerificationRun;
import com.verireview.verification.VerificationRunRepository;
import com.verireview.verification.VerificationService;
import com.verireview.verification.VerificationVerdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The find -&gt; ask permission -&gt; fix -&gt; re-check flow.
 *
 * <p>Consent is enforced on the backend: a fix run refuses to start unless an
 * approved {@link FixRequest} already exists for every selected finding and for
 * the requesting user (HTTP 409 otherwise). Patches are only proposed/applied
 * through the existing patch services, and the re-check reuses the existing
 * sandbox execution + verification chain. Every stage is recorded as a real
 * {@code CheckRun}/{@code CheckStep} so the pipeline view reflects true state.
 *
 * <p>The orchestrator deliberately runs WITHOUT a wrapping transaction so a
 * controlled failure in one step (e.g. the AI coding agent being unavailable)
 * cannot roll back the run-history rows recorded so far.
 */
@Service
public class FixRunService {

  private static final Pattern DIFF_GIT_HEADER =
      Pattern.compile("^diff --git a/(\\S+) b/\\S+", Pattern.MULTILINE);

  private final ProjectRepository projects;
  private final FindingRepository findings;
  private final FixRequestRepository fixRequests;
  private final PatchRepository patches;
  private final CheckRunRepository checkRuns;
  private final CheckStepRepository checkSteps;
  private final CheckRunService checkRunService;
  private final FixService fixService;
  private final PatchService patchService;
  private final PatchApplicationService patchApplication;
  private final ExecutionService executionService;
  private final ExecutionRunRepository executions;
  private final VerificationService verificationService;
  private final VerificationRunRepository verifications;
  private final TransactionTemplate tx;

  public FixRunService(
      ProjectRepository projects,
      FindingRepository findings,
      FixRequestRepository fixRequests,
      PatchRepository patches,
      CheckRunRepository checkRuns,
      CheckStepRepository checkSteps,
      CheckRunService checkRunService,
      FixService fixService,
      PatchService patchService,
      PatchApplicationService patchApplication,
      ExecutionService executionService,
      ExecutionRunRepository executions,
      VerificationService verificationService,
      VerificationRunRepository verifications,
      PlatformTransactionManager txManager) {
    this.projects = projects;
    this.findings = findings;
    this.fixRequests = fixRequests;
    this.patches = patches;
    this.checkRuns = checkRuns;
    this.checkSteps = checkSteps;
    this.checkRunService = checkRunService;
    this.fixService = fixService;
    this.patchService = patchService;
    this.patchApplication = patchApplication;
    this.executionService = executionService;
    this.executions = executions;
    this.verificationService = verificationService;
    this.verifications = verifications;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * The user's consent step: create approved fix requests for the selected findings.
   * Nothing is changed here -- this only records approval.
   */
  public BulkFixRequestCreated createFixRequests(
      UUID ownerId, UUID projectId, BulkFixRequestCreate request) {
    if (request.findingIds() == null || request.findingIds().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one finding");
    }
    tx.execute(status -> {
      projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
      for (UUID findingId : request.findingIds()) {
        requireFindingInProject(findingId, projectId);
      }
      return null;
    });

    List<UUID> createdIds = new ArrayList<>();
    int skipped = 0;
    for (UUID findingId : request.findingIds()) {
      boolean alreadyOpen = Boolean.TRUE.equals(tx.execute(status -> fixRequests
          .existsByFindingIdAndStatusIn(findingId,
              List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS))));
      if (alreadyOpen) {
        skipped++;
        continue;
      }
      var created = fixService.create(ownerId, findingId,
          new CreateFixRequestRequest(request.scopeNote()));
      createdIds.add(created.id());
    }
    return new BulkFixRequestCreated(projectId, createdIds, createdIds.size(), skipped);
  }

  /**
   * Starts a fix run: the permission gate runs first, then the coding agent is
   * invoked per approved fix request, the resulting patch is applied, and the
   * re-check (build/tests + verification) runs automatically.
   */
  public FixRunDetailResponse startFixRun(
      UUID ownerId, UUID projectId, StartFixRunRequest request) {
    if (request.findingIds() == null || request.findingIds().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one finding to fix");
    }

    Gate gate = tx.execute(status -> resolveGate(ownerId, projectId, request.findingIds()));
    if (gate == null) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not start fix run");
    }

    List<CheckRunService.CheckStepDefinition> fixSteps = List.of(
        new CheckRunService.CheckStepDefinition(0, "Propose fix"),
        new CheckRunService.CheckStepDefinition(1, "Apply fix"));
    List<CheckRunService.CheckStepDefinition> verifySteps = List.of(
        new CheckRunService.CheckStepDefinition(0, "Build and tests"),
        new CheckRunService.CheckStepDefinition(1, "Evaluate gates"));

    CheckRun fixRun = checkRunService.createCheckRun(
        ownerId, projectId, CheckFeature.FIX, null, fixSteps);
    CheckRun verifyRun = checkRunService.createCheckRun(
        ownerId, projectId, CheckFeature.VERIFY, null, verifySteps);

    checkRunService.markRunning(fixRun.getId());
    checkRunService.updateProgress(fixRun.getId(), 0, "Starting fix run");

    boolean anyApplied = false;
    UUID lastPatchId = null;

    try {
      int total = gate.fixRequestIds().size();
      int done = 0;
      for (UUID fixRequestId : gate.fixRequestIds()) {
        checkRunService.updateStep(fixRun.getId(), 0, CheckStatus.RUNNING, 10,
            "Proposing fix", null);
        PatchResponse proposed = patchService.proposePatch(ownerId, fixRequestId);
        checkRunService.updateStep(fixRun.getId(), 0, CheckStatus.SUCCEEDED, 100,
            "Fix proposed", null);

        checkRunService.updateStep(fixRun.getId(), 1, CheckStatus.RUNNING, 10,
            "Applying fix", null);
        patchApplication.applyPatch(ownerId, proposed.id());
        checkRunService.updateStep(fixRun.getId(), 1, CheckStatus.SUCCEEDED, 100,
            "Fix applied", null);
        anyApplied = true;
        lastPatchId = proposed.id();
        done++;
        checkRunService.updateProgress(fixRun.getId(),
            (int) Math.round(done * 100.0 / total), "Fix applied");
      }
      checkRunService.markSucceeded(fixRun.getId(),
          anyApplied ? "Fix applied" : "Nothing to change");
    } catch (ResponseStatusException e) {
      failRun(fixRun.getId(), verifyRun.getId(), e.getReason(),
          gate.fixRequestIds(), ownerId);
      return getFixRun(ownerId, projectId, fixRun.getId());
    } catch (Exception e) {
      failRun(fixRun.getId(), verifyRun.getId(), e.getMessage(),
          gate.fixRequestIds(), ownerId);
      return getFixRun(ownerId, projectId, fixRun.getId());
    }

    // ---- Automatic re-check (VERIFY) ----
    checkRunService.markRunning(verifyRun.getId());
    try {
      checkRunService.updateStep(verifyRun.getId(), 0, CheckStatus.RUNNING, 10,
          "Running build and tests", null);
      ExecutionRunResponse exec = executionService.execute(ownerId, projectId, lastPatchId);
      boolean skipped = exec.status() == ExecutionStatus.SKIPPED
          || exec.buildStatus() == com.verireview.verification.BuildStatus.SKIPPED;

      // Always persist real verification evidence. A skipped run produces a
      // PENDING verdict (never VERIFIED) so the UI can honestly say "not verified".
      VerificationRun verification = verificationService.verify(ownerId, exec.id());

      if (skipped) {
        checkRunService.updateStep(verifyRun.getId(), 0, CheckStatus.SKIPPED, 100,
            "Not verified: sandbox unavailable", exec.stdout());
        checkRunService.updateStep(verifyRun.getId(), 1, CheckStatus.SKIPPED, 100,
            "Not verified: sandbox unavailable", null);
        checkRunService.markSkipped(verifyRun.getId(), "Not verified: sandbox unavailable");
        // Applied but unverifiable: keep it open, never claim success.
        markFixRequests(gate, FixRequestStatus.IN_PROGRESS);
        return getFixRun(ownerId, projectId, fixRun.getId());
      }

      checkRunService.updateStep(verifyRun.getId(), 0,
          exec.status() == ExecutionStatus.SUCCESS ? CheckStatus.SUCCEEDED : CheckStatus.FAILED,
          100, exec.status() == ExecutionStatus.SUCCESS ? "Build and tests passed" : "Build or tests failed",
          exec.stdout());

      checkRunService.updateStep(verifyRun.getId(), 1, CheckStatus.RUNNING, 10,
          "Evaluating gates", null);
      boolean verified = verification.getVerdict() == VerificationVerdict.VERIFIED;
      checkRunService.updateStep(verifyRun.getId(), 1,
          verified ? CheckStatus.SUCCEEDED : CheckStatus.FAILED, 100,
          verified ? "All gates passed" : "Verification rejected", verification.getLogRef());

      if (verified) {
        checkRunService.markSucceeded(verifyRun.getId(), "Verified");
        markFixRequests(gate, FixRequestStatus.COMPLETED);
      } else {
        checkRunService.markFailed(verifyRun.getId(),
            "Verification rejected: build, tests, or new critical/high findings");
        markFixRequests(gate, FixRequestStatus.FAILED);
      }
    } catch (ResponseStatusException e) {
      checkRunService.markFailed(verifyRun.getId(), ErrorMessages.sanitize(e.getReason()));
      markFixRequests(gate, FixRequestStatus.FAILED);
    } catch (Exception e) {
      checkRunService.markFailed(verifyRun.getId(), ErrorMessages.sanitize(e.getMessage()));
      markFixRequests(gate, FixRequestStatus.FAILED);
    }

    return getFixRun(ownerId, projectId, fixRun.getId());
  }

  public FixRunDetailResponse getFixRun(UUID ownerId, UUID projectId, UUID checkRunId) {
    FixRunDetailResponse detail = tx.execute(status -> buildDetail(ownerId, projectId, checkRunId));
    if (detail == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix run not found");
    }
    return detail;
  }

  // ------------------------------------------------------------------ helpers

  private Gate resolveGate(UUID ownerId, UUID projectId, List<UUID> findingIds) {
    projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    List<UUID> fixRequestIds = new ArrayList<>();
    List<UUID> resolvedFindingIds = new ArrayList<>();
    List<UUID> missing = new ArrayList<>();
    for (UUID findingId : findingIds) {
      Finding finding = requireFindingInProject(findingId, projectId);
      FixRequest approved = fixRequests
          .findByFindingIdAndStatusIn(findingId,
              List.of(FixRequestStatus.REQUESTED, FixRequestStatus.IN_PROGRESS))
          .stream()
          .filter(fr -> fr.getRequestedBy() != null
              && ownerId.equals(fr.getRequestedBy().getId()))
          .findFirst()
          .orElse(null);
      if (approved == null) {
        missing.add(findingId);
      } else {
        fixRequestIds.add(approved.getId());
        resolvedFindingIds.add(finding.getId());
      }
    }
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "An approved fix request is required before a fix run can start. "
              + missing.size() + " selected finding(s) have no approved fix request.");
    }
    return new Gate(projectId, fixRequestIds, resolvedFindingIds);
  }

  private Finding requireFindingInProject(UUID findingId, UUID projectId) {
    Finding finding = findings.findById(findingId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found"));
    var review = finding.getReview();
    Project project = review != null ? review.getProject() : null;
    if (project == null || !project.getId().equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding not found");
    }
    return finding;
  }

  private void failRun(UUID fixRunId, UUID verifyRunId, String reason,
      List<UUID> fixRequestIds, UUID ownerId) {
    String message = ErrorMessages.sanitize(reason);
    checkRunService.markFailed(fixRunId, message);
    checkRunService.markFailed(verifyRunId, message);
    // Reset requests that never produced an APPLIED patch so a Retry can re-propose.
    for (UUID fixRequestId : fixRequestIds) {
      tx.execute(status -> {
        FixRequest fr = fixRequests.findById(fixRequestId).orElse(null);
        if (fr == null) {
          return null;
        }
        boolean hasApplied = patches.findByFixRequestIdOrderByCreatedAtDesc(fixRequestId).stream()
            .anyMatch(p -> p.getStatus() == PatchStatus.APPLIED);
        if (!hasApplied && fr.getStatus() == FixRequestStatus.IN_PROGRESS) {
          fr.setStatus(FixRequestStatus.REQUESTED);
          fixRequests.save(fr);
        }
        return null;
      });
    }
  }

  private void markFixRequests(Gate gate, FixRequestStatus status) {
    tx.execute(txStatus -> {
      for (int i = 0; i < gate.fixRequestIds().size(); i++) {
        FixRequest fr = fixRequests.findById(gate.fixRequestIds().get(i)).orElse(null);
        if (fr == null) {
          continue;
        }
        fr.setStatus(status);
        fixRequests.save(fr);
        Finding finding = fr.getFinding();
        if (status == FixRequestStatus.COMPLETED) {
          finding.setStatus(FindingStatus.VERIFIED_FIXED);
        } else if (status == FixRequestStatus.FAILED) {
          finding.setStatus(FindingStatus.OPEN);
        } else if (status == FixRequestStatus.IN_PROGRESS) {
          // Applied but unverified -> never claim it is fixed.
          finding.setStatus(FindingStatus.FIX_PROPOSED);
        }
        findings.save(finding);
      }
      return null;
    });
  }

  private FixRunDetailResponse buildDetail(UUID ownerId, UUID projectId, UUID checkRunId) {
    CheckRun run = checkRuns.findById(checkRunId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix run not found"));
    Project project = run.getProject();
    if (!project.getId().equals(projectId)
        || run.getFeature() != CheckFeature.FIX
        || project.getDeletedAt() != null
        || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fix run not found");
    }

    List<FixRunChangedFileResponse> changedFiles = new ArrayList<>();
    List<Patch> runPatches = runPatches(projectId, run);
    Set<UUID> selectedFindingIds = new HashSet<>();
    for (Patch patch : runPatches) {
      String status = patch.getStatus().name();
      String diff = patch.getDiff();
      String patchId = patch.getId().toString();
      List<String> paths = extractPaths(diff);
      if (paths.isEmpty()) {
        changedFiles.add(new FixRunChangedFileResponse("(unknown)", patchId, status, diff));
      } else {
        for (String path : paths) {
          changedFiles.add(new FixRunChangedFileResponse(path, patchId, status, diff));
        }
      }
      if (patch.getFixRequest() != null && patch.getFixRequest().getFinding() != null) {
        selectedFindingIds.add(patch.getFixRequest().getFinding().getId());
      }
    }

    FixRunSummaryResponse summary = summarize(projectId, run, selectedFindingIds);

    VerificationRun latestVerification = null;
    boolean sandboxSkipped = false;
    for (Patch patch : runPatches) {
      for (VerificationRun v : verifications.findByPatchIdOrderByCreatedAtDesc(patch.getId())) {
        if (latestVerification == null
            || v.getCreatedAt().isAfter(latestVerification.getCreatedAt())) {
          latestVerification = v;
        }
      }
      for (ExecutionRun e : executions.findByPatchIdOrderByCreatedAtDesc(patch.getId())) {
        if (e.getStatus() == ExecutionStatus.SKIPPED) {
          sandboxSkipped = true;
        }
      }
    }
    boolean verifyRunSkipped = checkRuns
        .findFirstByProjectIdAndFeatureOrderByCreatedAtDesc(projectId, CheckFeature.VERIFY)
        .map(vr -> vr.getStatus() == CheckStatus.SKIPPED)
        .orElse(false);

    List<CheckStepResponse> steps = checkSteps.findByCheckRunIdOrderByStepOrderAsc(checkRunId)
        .stream().map(FixRunService::toStepResponse).toList();

    return new FixRunDetailResponse(
        run.getId(),
        projectId,
        run.getStatus().name(),
        ErrorMessages.sanitize(run.getErrorMessage()),
        steps,
        summary,
        changedFiles,
        latestVerification != null ? latestVerification.getVerdict().name() : null,
        sandboxSkipped || verifyRunSkipped,
        run.getStartedAt(),
        run.getFinishedAt());
  }

  private List<Patch> runPatches(UUID projectId, CheckRun run) {
    Instant since = run.getCreatedAt();
    List<Patch> result = new ArrayList<>();
    for (FixRequest fr : fixRequests.findByFindingReviewProjectId(projectId)) {
      Patch latest = patches.findTopByFixRequestIdOrderByCreatedAtDesc(fr.getId()).orElse(null);
      if (latest != null && (since == null || !latest.getCreatedAt().isBefore(since))) {
        result.add(latest);
      }
    }
    return result;
  }

  private FixRunSummaryResponse summarize(UUID projectId, CheckRun run, Set<UUID> selectedFindingIds) {
    int fixed = 0;
    int stillOpen = 0;
    for (UUID findingId : selectedFindingIds) {
      Finding finding = findings.findById(findingId).orElse(null);
      if (finding == null) {
        continue;
      }
      if (finding.getStatus() == FindingStatus.VERIFIED_FIXED) {
        fixed++;
      } else if (finding.getStatus() == FindingStatus.OPEN
          || finding.getStatus() == FindingStatus.FIX_PROPOSED) {
        stillOpen++;
      }
    }
    Instant since = run.getCreatedAt() != null ? run.getCreatedAt() : Instant.EPOCH;
    List<Finding> after = findings.findByReviewProjectIdAndCreatedAtAfter(projectId, since);
    int newlyIntroduced = 0;
    for (Finding finding : after) {
      if (!selectedFindingIds.contains(finding.getId())) {
        newlyIntroduced++;
      }
    }
    return new FixRunSummaryResponse(fixed, stillOpen, newlyIntroduced);
  }

  private static List<String> extractPaths(String diff) {
    List<String> paths = new ArrayList<>();
    if (diff == null) {
      return paths;
    }
    Matcher matcher = DIFF_GIT_HEADER.matcher(diff);
    while (matcher.find()) {
      paths.add(matcher.group(1));
    }
    return paths;
  }

  private static CheckStepResponse toStepResponse(CheckStep step) {
    return new CheckStepResponse(
        step.getId(),
        step.getCheckRun().getId(),
        step.getStepOrder(),
        step.getName(),
        step.getStatus().name(),
        step.getProgress(),
        step.getCurrentMessage(),
        step.getErrorMessage(),
        step.getStartedAt(),
        step.getFinishedAt(),
        step.getDurationMs(),
        step.getLogTail());
  }

  private record Gate(UUID projectId, List<UUID> fixRequestIds, List<UUID> findingIds) {}
}
