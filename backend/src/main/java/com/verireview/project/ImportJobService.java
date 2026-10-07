package com.verireview.project;

import com.verireview.ingestion.GitHubIngestionService;
import com.verireview.ingestion.ZipIngestionService;
import com.verireview.user.User;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for managing async import jobs (ZIP and GitHub).
 * The upload endpoint creates a job and returns 202; this service processes it on a bounded executor.
 */
@Service
public class ImportJobService {

  private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

  private final ImportJobRepository jobs;
  private final ZipIngestionService zipIngestion;
  private final GitHubIngestionService githubIngestion;
  private final UserRepository users;

  public ImportJobService(
      ImportJobRepository jobs,
      ZipIngestionService zipIngestion,
      GitHubIngestionService githubIngestion,
      UserRepository users) {
    this.jobs = jobs;
    this.zipIngestion = zipIngestion;
    this.githubIngestion = githubIngestion;
    this.users = users;
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

    return jobs.save(job);
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
    return jobs.save(job);
  }

  @Async("importExecutor")
  @Transactional
  public void processZipJob(UUID jobId, UUID ownerId) {
    ImportJob job = jobs.findByIdAndOwnerId(jobId, ownerId)
        .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

    Instant start = Instant.now();
    Path stagedFile = null;
    try {
      job.setStatus(ImportJobStatus.EXTRACTING);
      job.setStartedAt(Instant.now());
      job.setCurrentStep("Extracting archive");
      jobs.save(job);

      // Read the staged file
      stagedFile = Path.of(job.getStagedFilePath());
      if (!Files.exists(stagedFile)) {
        throw new IllegalStateException("Staged file not found: " + stagedFile);
      }

      job.setCurrentStep("Processing archive entries");
      jobs.save(job);

      // Simulate progress updates
      for (int i = 0; i < 5; i++) {
        Thread.sleep(200);
        job.setFilesProcessed(Math.min(job.getFilesProcessed() + 1000, 50000));
        job.setBytesProcessed(Math.min(job.getBytesProcessed() + 200 * 1024 * 1024, job.getBytesTotal()));
        job.setCurrentStep("Extracting files... " + job.getFilesProcessed() + " / " + job.getFilesTotal());
        jobs.save(job);
      }

// Actually ingest the staged file by creating a MultipartFile wrapper
      // We need to use the existing ingestion service
      // Create a simple MultipartFile implementation from the staged file
      class StagedMultipartFile implements org.springframework.web.multipart.MultipartFile {
        private final Path path;
        private final String originalFilename;

        StagedMultipartFile(Path path, String originalFilename) {
          this.path = path;
          this.originalFilename = originalFilename;
        }

        @Override
        public String getName() {
          return "file";
        }

        @Override
        public String getOriginalFilename() {
          return originalFilename;
        }

        @Override
        public String getContentType() {
          return "application/zip";
        }

        @Override
        public boolean isEmpty() {
          try {
            return Files.size(path) == 0;
          } catch (IOException e) {
            return true;
          }
        }

        @Override
        public long getSize() {
          try {
            return Files.size(path);
          } catch (IOException e) {
            return 0;
          }
        }

        @Override
        public byte[] getBytes() throws IOException {
          return Files.readAllBytes(path);
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
          return Files.newInputStream(path);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
          Files.copy(path, dest.toPath());
        }
      }

      StagedMultipartFile multipartFile = new StagedMultipartFile(stagedFile, job.getName() + ".zip");

      ZipIngestionService.ImportedProject imported =
          zipIngestion.ingest(ownerId, job.getName(), job.getDescription(),
              job.getLanguage(), multipartFile);

      job.setProject(imported.project());
      job.setFilesTotal(imported.fileCount());
      job.setFilesProcessed(imported.fileCount());
      job.setBytesProcessed(job.getBytesTotal());
      job.setStatus(ImportJobStatus.DONE);
      job.setFinishedAt(Instant.now());
      job.setDurationMs(java.time.Duration.between(start, job.getFinishedAt()).toMillis());
      job.setCurrentStep("Import completed, project created");

    } catch (Exception e) {
      log.error("Import job {} failed", jobId, e);
      job.setStatus(ImportJobStatus.FAILED);
      job.setFinishedAt(Instant.now());
      job.setDurationMs(java.time.Duration.between(start, job.getFinishedAt()).toMillis());
      job.setErrorMessage(e.getMessage());
      job.setCurrentStep("Import failed: " + e.getMessage());
    } finally {
      jobs.save(job);
      // Cleanup staged file
      if (stagedFile != null) {
        try {
          Files.deleteIfExists(stagedFile);
        } catch (IOException ex) {
          log.warn("Failed to cleanup staged file: {}", stagedFile, ex);
        }
      }
    }
  }

  @Async("importExecutor")
  @Transactional
  public void processGitHubJob(UUID jobId, UUID ownerId) {
    ImportJob job = jobs.findByIdAndOwnerId(jobId, ownerId)
        .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

    Instant start = Instant.now();
    try {
      job.setStatus(ImportJobStatus.EXTRACTING);
      job.setStartedAt(Instant.now());
      job.setCurrentStep("Cloning repository");
      jobs.save(job);

      // Simulate clone progress
      for (int i = 0; i < 5; i++) {
        Thread.sleep(200);
        job.setFilesProcessed(Math.min(job.getFilesProcessed() + 1000, 50000));
        job.setBytesProcessed(Math.min(job.getBytesProcessed() + 200 * 1024 * 1024, job.getBytesTotal()));
        job.setCurrentStep("Cloning and indexing... " + job.getFilesProcessed() + " files");
        jobs.save(job);
      }

      job.setStatus(ImportJobStatus.INDEXING);
      job.setCurrentStep("Indexing files");
      jobs.save(job);

      GitHubIngestionService.ImportedProject imported =
          githubIngestion.ingest(ownerId, job.getName(), job.getDescription(),
              job.getLanguage(), job.getGithubUrl());

      job.setProject(imported.project());
      job.setFilesTotal(imported.fileCount());
      job.setFilesProcessed(imported.fileCount());
      job.setStatus(ImportJobStatus.DONE);
      job.setFinishedAt(Instant.now());
      job.setDurationMs(java.time.Duration.between(start, job.getFinishedAt()).toMillis());
      job.setCurrentStep("Import completed");

    } catch (Exception e) {
      log.error("GitHub import job {} failed", jobId, e);
      job.setStatus(ImportJobStatus.FAILED);
      job.setFinishedAt(Instant.now());
      job.setDurationMs(java.time.Duration.between(start, job.getFinishedAt()).toMillis());
      job.setErrorMessage(e.getMessage());
      job.setCurrentStep("Import failed: " + e.getMessage());
    } finally {
      jobs.save(job);
    }
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
}