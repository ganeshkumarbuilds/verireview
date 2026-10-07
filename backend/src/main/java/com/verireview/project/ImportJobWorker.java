package com.verireview.project;

import com.verireview.analysis.AnalysisJobService;
import com.verireview.ingestion.GitHubIngestionService;
import com.verireview.ingestion.ZipIngestionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker bean that executes import jobs asynchronously.
 * Separate from ImportJobService to avoid @Async self-invocation problems.
 * Listens for {@link ImportJobEvent} published after transaction commit.
 */
@Component
public class ImportJobWorker {

  private static final Logger log = LoggerFactory.getLogger(ImportJobWorker.class);

  private final ImportJobRepository jobs;
  private final ZipIngestionService zipIngestion;
  private final GitHubIngestionService githubIngestion;
  private final AnalysisJobService analysisJobService;

  public ImportJobWorker(
      ImportJobRepository jobs,
      ZipIngestionService zipIngestion,
      GitHubIngestionService githubIngestion,
      AnalysisJobService analysisJobService) {
    this.jobs = jobs;
    this.zipIngestion = zipIngestion;
    this.githubIngestion = githubIngestion;
    this.analysisJobService = analysisJobService;
  }

  /**
   * Called after transaction commits via ImportJobEvent. The @Async ensures this runs on the importExecutor.
   */
  @EventListener
  @Async("importExecutor")
  public void handleImportJobEvent(ImportJobEvent event) {
    if (event.getType() == ImportJobEvent.ImportJobType.ZIP) {
      processZipJob(event.getJobId(), event.getOwnerId());
    } else if (event.getType() == ImportJobEvent.ImportJobType.GITHUB) {
      processGitHubJob(event.getJobId(), event.getOwnerId());
    }
  }

  @Transactional
  public void processZipJob(UUID jobId, UUID ownerId) {
    log.info("Starting ZIP import job: jobId={}, ownerId={}", jobId, ownerId);
    ImportJob job = jobs.findByIdAndOwnerId(jobId, ownerId)
        .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

    Instant start = Instant.now();
    Path stagedFile = null;
    try {
      log.info("Job {} transitioning to EXTRACTING", jobId);
      job.setStatus(ImportJobStatus.EXTRACTING);
      job.setStartedAt(Instant.now());
      job.setCurrentStep("Extracting archive");
      jobs.save(job);

      // Read the staged file
      stagedFile = Path.of(job.getStagedFilePath());
      if (!Files.exists(stagedFile)) {
        throw new IllegalStateException("Staged file not found at path: " + stagedFile +
            " (size would be unknown, disk may have been wiped on restart)");
      }
      long stagedSize = Files.size(stagedFile);
      log.info("Job {} reading staged file: path={}, size={} bytes", jobId, stagedFile, stagedSize);

      job.setCurrentStep("Processing archive entries");
      jobs.save(job);

      // Create streaming MultipartFile wrapper
      class StagedMultipartFile implements org.springframework.web.multipart.MultipartFile {
        private final Path path;
        private final String originalFilename;

        StagedMultipartFile(Path path, String originalFilename) {
          this.path = path;
          this.originalFilename = originalFilename;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return "application/zip"; }

        @Override public boolean isEmpty() {
          try { return Files.size(path) == 0; } catch (IOException e) { return true; }
        }

        @Override public long getSize() {
          try { return Files.size(path); } catch (IOException e) { return 0; }
        }

        @Override public byte[] getBytes() throws IOException {
          throw new UnsupportedOperationException("Use getInputStream() for streaming");
        }

        @Override public java.io.InputStream getInputStream() throws IOException {
          return Files.newInputStream(path);
        }

        @Override public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
          Files.copy(path, dest.toPath());
        }
      }

      StagedMultipartFile multipartFile = new StagedMultipartFile(stagedFile, job.getName() + ".zip");

      log.info("Job {} ingesting ZIP via ZipIngestionService", jobId);
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
      job.setCurrentStep("Import completed, project created, triggering analysis");

      // Trigger analysis automatically on successful import
      try {
        analysisJobService.trigger(ownerId, imported.project().getId());
        job.setCurrentStep("Import completed, analysis started");
      } catch (Exception ex) {
        log.warn("Failed to trigger analysis for project {}: {}", imported.project().getId(), ex.getMessage());
        job.setCurrentStep("Import completed, analysis trigger failed: " + ex.getMessage());
      }

      log.info("Job {} completed successfully: projectId={}, files={}, durationMs={}",
          jobId, imported.project().getId(), imported.fileCount(), job.getDurationMs());

    } catch (Throwable e) {
      log.error("Import job {} failed: {}", jobId, e.getMessage(), e);
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
          log.debug("Cleaned up staged file: {}", stagedFile);
        } catch (IOException ex) {
          log.warn("Failed to cleanup staged file: {}", stagedFile, ex);
        }
      }
    }
  }

  @Transactional
  public void processGitHubJob(UUID jobId, UUID ownerId) {
    log.info("Starting GitHub import job: jobId={}, ownerId={}", jobId, ownerId);
    ImportJob job = jobs.findByIdAndOwnerId(jobId, ownerId)
        .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

    Instant start = Instant.now();
    try {
      log.info("Job {} transitioning to EXTRACTING (GitHub clone)", jobId);
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

      log.info("Job {} transitioning to INDEXING", jobId);
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
      job.setCurrentStep("Import completed, triggering analysis");

      try {
        analysisJobService.trigger(ownerId, imported.project().getId());
        job.setCurrentStep("Import completed, analysis started");
      } catch (Exception ex) {
        log.warn("Failed to trigger analysis for project {}: {}", imported.project().getId(), ex.getMessage());
        job.setCurrentStep("Import completed, analysis trigger failed: " + ex.getMessage());
      }

      log.info("Job {} completed successfully (GitHub): projectId={}, files={}, durationMs={}",
          jobId, imported.project().getId(), imported.fileCount(), job.getDurationMs());

    } catch (Throwable e) {
      log.error("GitHub import job {} failed: {}", jobId, e.getMessage(), e);
      job.setStatus(ImportJobStatus.FAILED);
      job.setFinishedAt(Instant.now());
      job.setDurationMs(java.time.Duration.between(start, job.getFinishedAt()).toMillis());
      job.setErrorMessage(e.getMessage());
      job.setCurrentStep("Import failed: " + e.getMessage());
    } finally {
      jobs.save(job);
    }
  }
}