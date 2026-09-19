package com.verireview.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable finding identity (DATABASE_DESIGN {@code dedup_key}): SHA-256 over
 * analyzer, rule, path, line, and message. Identical tool hits on reruns
 * produce identical keys, so reruns are reproducible and duplicates collapse.
 * Tool versions are deliberately excluded — identity is about the issue, and
 * the versions that produced a run are recorded with the review evidence.
 */
public final class Fingerprint {

  private Fingerprint() {
  }

  public static String of(
      String analyzer, String rule, String filePath, Integer line, String message) {
    String joined = String.join("\0",
        nullToEmpty(analyzer), nullToEmpty(rule), nullToEmpty(filePath),
        line == null ? "" : line.toString(), nullToEmpty(message));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
