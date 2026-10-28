package com.verireview.analysis;

import java.util.List;

/** One analyzer's outcome within a sandboxed run. */
public record ToolReport(
    String analyzer,
    ToolStatus status,
    String detail,
    List<NormalizedFinding> findings,
    long durationMs) {

  public enum ToolStatus {
    RAN,
    SKIPPED_NO_JAVA,
    SKIPPED_NO_BUILD_FILE,
    SKIPPED_NO_CLASSES,
    SKIPPED_COMPILE_FAILED,
    SKIPPED_DISABLED,
    FAILED
  }

  public static ToolReport ran(String analyzer, List<NormalizedFinding> findings) {
    return new ToolReport(analyzer, ToolStatus.RAN, null, findings, 0);
  }

  public static ToolReport skipped(String analyzer, ToolStatus status, String detail) {
    return new ToolReport(analyzer, status, detail, List.of(), 0);
  }

  public static ToolReport failed(String analyzer, String detail) {
    return new ToolReport(analyzer, ToolStatus.FAILED, detail, List.of(), 0);
  }

  public ToolReport withDuration(long duration) {
    return new ToolReport(analyzer, status, detail, findings, duration);
  }
}
