package com.verireview.ingestion;

import org.springframework.http.HttpStatus;

/**
 * Exception for ZIP import failures with structured error details.
 */
public class ZipImportException extends RuntimeException {

  private final String code;
  private final HttpStatus httpStatus;
  private final String fileName;
  private final long fileSize;
  private final int fileCount;

  public ZipImportException(String code, String message, HttpStatus httpStatus,
                            String fileName, long fileSize, int fileCount) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.fileName = fileName;
    this.fileSize = fileSize;
    this.fileCount = fileCount;
  }

  public String getCode() {
    return code;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  public String getFileName() {
    return fileName;
  }

  public long getFileSize() {
    return fileSize;
  }

  public int getFileCount() {
    return fileCount;
  }

  public static ZipImportException oversizedZip(String fileName, long fileSize, long maxSize) {
    return new ZipImportException(
        "zip_too_large",
        String.format("Archive size %s exceeds the maximum allowed size of %s.",
            formatBytes(fileSize), formatBytes(maxSize)),
        HttpStatus.PAYLOAD_TOO_LARGE,
        fileName, fileSize, 0);
  }

  public static ZipImportException tooManyFiles(String fileName, long fileSize, int fileCount, int maxFiles) {
    return new ZipImportException(
        "too_many_files",
        String.format("Archive contains %d files, exceeding the limit of %d.", fileCount, maxFiles),
        HttpStatus.UNPROCESSABLE_ENTITY,
        fileName, fileSize, fileCount);
  }

  public static ZipImportException zipSlip(String fileName, long fileSize, String entryName) {
    return new ZipImportException(
        "zip_slip",
        "Archive entry escapes the project: " + entryName,
        HttpStatus.BAD_REQUEST,
        fileName, fileSize, 0);
  }

  public static ZipImportException corruptZip(String fileName, long fileSize, String reason) {
    return new ZipImportException(
        "corrupt_zip",
        "File is not a readable ZIP archive: " + reason,
        HttpStatus.BAD_REQUEST,
        fileName, fileSize, 0);
  }

  public static ZipImportException emptyArchive(String fileName, long fileSize) {
    return new ZipImportException(
        "empty_archive",
        "Archive contains no files",
        HttpStatus.BAD_REQUEST,
        fileName, fileSize, 0);
  }

  public static ZipImportException oversizedFile(String fileName, long fileSize, String entryName, long maxFileSize) {
    return new ZipImportException(
        "file_too_large",
        String.format("Archive contains an oversized file '%s' (%s exceeds %s limit).",
            entryName, formatBytes(fileSize), formatBytes(maxFileSize)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        fileName, fileSize, 0);
  }

  public static ZipImportException uncompressedTooLarge(String fileName, long fileSize, long totalUncompressed, long maxUncompressed) {
    return new ZipImportException(
        "uncompressed_too_large",
        String.format("Archive uncompressed size %s exceeds the maximum allowed %s.",
            formatBytes(totalUncompressed), formatBytes(maxUncompressed)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        fileName, fileSize, 0);
  }

  public static ZipImportException symlinkDetected(String fileName, long fileSize, String entryName) {
    return new ZipImportException(
        "symlink_detected",
        "Archive contains a symlink: " + entryName,
        HttpStatus.BAD_REQUEST,
        fileName, fileSize, 0);
  }

  public static ZipImportException invalidExtension(String fileName, long fileSize) {
    return new ZipImportException(
        "invalid_extension",
        "Only .zip uploads are accepted",
        HttpStatus.BAD_REQUEST,
        fileName, fileSize, 0);
  }

  private static String formatBytes(long bytes) {
    if (bytes >= 1024L * 1024L * 1024L) {
      return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    } else if (bytes >= 1024L * 1024L) {
      return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    } else if (bytes >= 1024L) {
      return String.format("%.1f KB", bytes / 1024.0);
    }
    return bytes + " bytes";
  }
}