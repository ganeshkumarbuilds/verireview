package com.verireview.analysis;

/**
 * Pinned analyzer versions (must match {@code docker/analysis/Dockerfile} and
 * the baked {@code /opt/versions.properties}; asserted by tests).
 */
public final class ToolVersions {

  public static final String CHECKSTYLE = "10.12.7";
  public static final String PMD = "7.5.0";
  public static final String SPOTBUGS = "4.8.6";

  private ToolVersions() {
  }
}
