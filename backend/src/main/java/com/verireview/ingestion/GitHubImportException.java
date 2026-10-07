package com.verireview.ingestion;

import org.springframework.http.HttpStatus;

/**
 * Exception for GitHub import failures with structured error details.
 */
public class GitHubImportException extends RuntimeException {

  private final String code;
  private final HttpStatus httpStatus;
  private final String url;

  public GitHubImportException(String code, String message, HttpStatus httpStatus, String url) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
    this.url = url;
  }

  public String getCode() {
    return code;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  public String getUrl() {
    return url;
  }

  public static GitHubImportException invalidUrl(String url, String reason) {
    return new GitHubImportException(
        "invalid_github_url",
        "Invalid GitHub URL: " + reason,
        HttpStatus.BAD_REQUEST,
        url);
  }

  public static GitHubImportException hostNotAllowed(String url) {
    return new GitHubImportException(
        "github_host_not_allowed",
        "Only public GitHub repositories are allowed (github.com)",
        HttpStatus.BAD_REQUEST,
        url);
  }

  public static GitHubImportException cloneFailed(String url, String reason) {
    return new GitHubImportException(
        "github_clone_failed",
        "Could not clone repository: " + reason,
        HttpStatus.BAD_REQUEST,
        url);
  }

  public static GitHubImportException noCommits(String url) {
    return new GitHubImportException(
        "github_no_commits",
        "Repository has no commits",
        HttpStatus.BAD_REQUEST,
        url);
  }

  public static GitHubImportException tooManyFiles(String url, int fileCount, int maxFiles) {
    return new GitHubImportException(
        "too_many_files",
        String.format("Repository contains %d files, exceeding the limit of %d.", fileCount, maxFiles),
        HttpStatus.UNPROCESSABLE_ENTITY,
        url);
  }

  public static GitHubImportException oversizedFile(String url, String filePath, long fileSize, long maxFileSize) {
    return new GitHubImportException(
        "file_too_large",
        String.format("Repository contains an oversized file '%s' (%s exceeds %s limit).",
            filePath, formatBytes(fileSize), formatBytes(maxFileSize)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        url);
  }

  public static GitHubImportException uncompressedTooLarge(String url, long totalUncompressed, long maxUncompressed) {
    return new GitHubImportException(
        "uncompressed_too_large",
        String.format("Repository uncompressed size %s exceeds the maximum allowed %s.",
            formatBytes(totalUncompressed), formatBytes(maxUncompressed)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        url);
  }

  public static GitHubImportException emptyRepository(String url) {
    return new GitHubImportException(
        "empty_repository",
        "Repository contains no files",
        HttpStatus.BAD_REQUEST,
        url);
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