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
      @Value("${app.import.max-zip-bytes:1073741824}") long maxZipBytes,
      @Value("${app.import.max-files:50000}") int maxFiles,
      @Value("${app.import.max-total-uncompressed-bytes:4294967296}") long maxTotalUncompressedBytes,
      @Value("${app.import.max-single-file-bytes:10485760}") long maxSingleFileBytes) {
    this.maxZipBytes = maxZipBytes;
    this.maxFiles = maxFiles;
    this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
    this.maxSingleFileBytes = maxSingleFileBytes;
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
