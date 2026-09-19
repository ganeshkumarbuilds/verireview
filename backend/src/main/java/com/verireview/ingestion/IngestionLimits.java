package com.verireview.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Approved ingestion caps (API_DESIGN §4, ADR-007 values). Single source of
 * truth for the ZIP pipeline; overridable via environment for tests/ops.
 */
@Component
public class IngestionLimits {

  private final long maxZipBytes;
  private final int maxFiles;
  private final long maxTotalUncompressedBytes;
  private final long maxSingleFileBytes;

  public IngestionLimits(
      @Value("${app.ingestion.max-zip-mb:50}") long maxZipMb,
      @Value("${app.ingestion.max-files:2000}") int maxFiles,
      @Value("${app.ingestion.max-total-uncompressed-mb:200}") long maxTotalUncompressedMb,
      @Value("${app.ingestion.max-single-file-mb:10}") long maxSingleFileMb) {
    this.maxZipBytes = maxZipMb * 1024L * 1024L;
    this.maxFiles = maxFiles;
    this.maxTotalUncompressedBytes = maxTotalUncompressedMb * 1024L * 1024L;
    this.maxSingleFileBytes = maxSingleFileMb * 1024L * 1024L;
  }

  public long maxZipBytes() {
    return maxZipBytes;
  }

  public int maxFiles() {
    return maxFiles;
  }

  public long maxTotalUncompressedBytes() {
    return maxTotalUncompressedBytes;
  }

  public long maxSingleFileBytes() {
    return maxSingleFileBytes;
  }
}
