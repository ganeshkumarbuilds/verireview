package com.verireview.project;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles stale import jobs on application startup.
 * Any job stuck in EXTRACTING or INDEXING (e.g., from a crash or OOM kill)
 * is marked as FAILED so it can be retried.
 */
@Component
public class ImportJobReconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ImportJobReconciler.class);

  private final ImportJobRepository jobs;

  public ImportJobReconciler(ImportJobRepository jobs) {
    this.jobs = jobs;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    List<ImportJob> staleJobs = jobs.findByStatusIn(
        List.of(ImportJobStatus.EXTRACTING, ImportJobStatus.INDEXING));
    if (!staleJobs.isEmpty()) {
      log.warn("Found {} stale import jobs in EXTRACTING/INDEXING state, marking as FAILED",
          staleJobs.size());
      for (ImportJob job : staleJobs) {
        job.setStatus(ImportJobStatus.FAILED);
        job.setErrorMessage("Import interrupted by application restart (was " + job.getStatus() + ")");
        job.setCurrentStep("Import failed: interrupted by restart");
        job.setFinishedAt(java.time.Instant.now());
        if (job.getStartedAt() != null) {
          job.setDurationMs(java.time.Duration.between(job.getStartedAt(), job.getFinishedAt()).toMillis());
        }
      }
      jobs.saveAll(staleJobs);
    }
  }
}