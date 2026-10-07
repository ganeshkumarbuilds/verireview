package com.verireview.project;

import com.verireview.ingestion.GitHubIngestionService;
import com.verireview.ingestion.ZipIngestionService;
import com.verireview.user.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for managing async import jobs (ZIP and GitHub).
 * The upload endpoint creates a job and returns 202; processing happens via
 * {@link ImportJobWorker} which listens for {@link ImportJobEvent} after commit.
 */
@Service
public class ImportJobService {

  private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

  private final ImportJobRepository jobs;
  private final UserRepository users;
  private final ApplicationEventPublisher eventPublisher;

  public ImportJobService(
      ImportJobRepository jobs,
      UserRepository users,
      ApplicationEventPublisher eventPublisher) {
    this.jobs = jobs;
    this.users = users;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public ImportJob createZipJob(UUID ownerId, String name, String description, String language,
                                MultipartFile file) {
    String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
    if (!filename.toLowerCase().endsWith(".zip")) {
      throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "Only .zip uploads are accepted");
    }

    Path staged;
    try {
      staged = Files.createTempFile("verireview-import-", ".zip");
      file.transferTo(staged);
      long stagedSize = Files.size(staged);
      log.info("Job file staged: path={}, size={} bytes", staged, stagedSize);
    } catch (IOException e) {
      throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "Could not stage the uploaded archive");
    }

    ImportJob job = new ImportJob(users.getReferenceById(ownerId), ProjectSourceType.ZIP_UPLOAD, name);
    job.setDescription(description);
    job.setLanguage(language);
    job.setBytesTotal(file.getSize());
    job.setStatus(ImportJobStatus.QUEUED);
    job.setCurrentStep("File staged, waiting for processing");
    job.setStagedFilePath(staged.toString());

    ImportJob saved = jobs.save(job);
    log.info("Import job created: jobId={}, ownerId={}, name={}, status=QUEUED", saved.getId(), ownerId, name);

    // Publish event AFTER commit to trigger async processing
    eventPublisher.publishEvent(new ImportJobEvent(saved.getId(), ownerId, ImportJobEvent.ImportJobType.ZIP));

    return saved;
  }

  @Transactional
  public ImportJob createGitHubJob(UUID ownerId, String name, String description, String language,
                                   String url) {
    ImportJob job = new ImportJob(users.getReferenceById(ownerId), ProjectSourceType.GITHUB, name);
    job.setDescription(description);
    job.setLanguage(language);
    job.setGithubUrl(url);
    job.setStatus(ImportJobStatus.QUEUED);
    job.setCurrentStep("GitHub import queued");

    ImportJob saved = jobs.save(job);
    log.info("Import job created: jobId={}, ownerId={}, name={}, status=QUEUED", saved.getId(), ownerId, name);

    // Publish event AFTER commit to trigger async processing
    eventPublisher.publishEvent(new ImportJobEvent(saved.getId(), ownerId, ImportJobEvent.ImportJobType.GITHUB));

    return saved;
  }

  @Transactional(readOnly = true)
  public Page<ImportJob> findByOwner(UUID ownerId, Pageable pageable) {
    return jobs.findByOwnerId(ownerId, pageable);
  }

  @Transactional(readOnly = true)
  public Optional<ImportJob> findByIdAndOwner(UUID jobId, UUID ownerId) {
    return jobs.findByIdAndOwnerId(jobId, ownerId);
  }

  @Transactional(readOnly = true)
  public long countRunningJobs(UUID ownerId) {
    return jobs.countByOwnerIdAndStatusIn(ownerId,
        List.of(ImportJobStatus.QUEUED, ImportJobStatus.EXTRACTING, ImportJobStatus.INDEXING));
  }

  /**
   * Cancel a queued job by marking it FAILED.
   */
  @Transactional
  public void cancelJob(UUID jobId, UUID ownerId) {
    ImportJob job = jobs.findByIdAndOwnerId(jobId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
            "Import job not found"));
    if (job.getStatus() != ImportJobStatus.QUEUED) {
      throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
          "Only QUEUED jobs can be cancelled");
    }
    job.setStatus(ImportJobStatus.FAILED);
    job.setErrorMessage("Cancelled by user");
    job.setCurrentStep("Import cancelled by user");
    job.setFinishedAt(Instant.now());
    jobs.save(job);
    log.info("Import job cancelled: jobId={}", jobId);
  }
}