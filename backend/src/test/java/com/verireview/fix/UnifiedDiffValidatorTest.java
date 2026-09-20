package com.verireview.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verireview.fix.UnifiedDiffValidator.DiffStats;
import com.verireview.fix.UnifiedDiffValidator.DiffValidationException;
import org.junit.jupiter.api.Test;

/**
 * Phase 13A: strict Coding Agent diff gate. Pure unit tests (no Spring) —
 * malformed, absolute, traversal, binary, and oversized diffs must be
 * rejected before anything is persisted.
 */
class UnifiedDiffValidatorTest {

  private static final String VALID =
      "diff --git a/src/Main.java b/src/Main.java\n"
          + "--- a/src/Main.java\n"
          + "+++ b/src/Main.java\n"
          + "@@ -10,3 +10,4 @@\n"
          + " context\n"
          + "- old\n"
          + "+ new\n"
          + "+ added";

  @Test
  void acceptsValidDiffAndDerivesStats() {
    DiffStats stats = UnifiedDiffValidator.validate(VALID);
    assertThat(stats.filesChanged()).isEqualTo(1);
    assertThat(stats.additions()).isEqualTo(2);
    assertThat(stats.deletions()).isEqualTo(1);
    assertThat(stats.files()).containsExactly("src/Main.java");
  }

  @Test
  void acceptsNewFileDevNullDiff() {
    String diff = "diff --git a/src/New.java b/src/New.java\n"
        + "--- /dev/null\n"
        + "+++ b/src/New.java\n"
        + "@@ -0,0 +1 @@\n"
        + "+class New {}";
    DiffStats stats = UnifiedDiffValidator.validate(diff);
    assertThat(stats.filesChanged()).isEqualTo(1);
    assertThat(stats.additions()).isEqualTo(1);
  }

  @Test
  void rejectsMalformedDiff() {
    assertThatThrownBy(() -> UnifiedDiffValidator.validate("not a diff"))
        .isInstanceOf(DiffValidationException.class);
    assertThatThrownBy(() -> UnifiedDiffValidator.validate("   "))
        .isInstanceOf(DiffValidationException.class);
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(null))
        .isInstanceOf(DiffValidationException.class);
  }

  @Test
  void rejectsTraversalPaths() {
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(withPaths("../evil.sh")))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("illegal path");
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(withPaths("a/../../etc/x")))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("illegal path");
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(withPaths("src/..\\win")))
        .isInstanceOf(DiffValidationException.class);
  }

  @Test
  void rejectsAbsolutePaths() {
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(withPaths("/etc/passwd")))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("absolute path");
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(withPaths("C:/Windows/x")))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("absolute path");
  }

  @Test
  void rejectsBinaryDiff() {
    String diff = "diff --git a/img.png b/img.png\n"
        + "Binary files a/img.png and b/img.png differ";
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(diff))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("binary");
  }

  @Test
  void rejectsMismatchedHeadersAndMissingHunks() {
    String mismatched = "diff --git a/A.java b/A.java\n"
        + "--- a/A.java\n"
        + "+++ b/B.java\n"
        + "@@ -1 +1 @@\n"
        + "+x";
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(mismatched))
        .isInstanceOf(DiffValidationException.class);

    String noHunk = "diff --git a/A.java b/A.java\n"
        + "--- a/A.java\n"
        + "+++ b/A.java\n";
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(noHunk))
        .isInstanceOf(DiffValidationException.class);
  }

  @Test
  void rejectsVerificationClaims() {
    String claimed = VALID + "\n+// tests passed";
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(claimed))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("verification");
  }

  @Test
  void rejectsTooManyFiles() {
    StringBuilder diff = new StringBuilder();
    for (int i = 0; i < UnifiedDiffValidator.MAX_FILES + 1; i++) {
      diff.append("diff --git a/F").append(i).append(".java b/F").append(i).append(".java\n")
          .append("--- a/F").append(i).append(".java\n")
          .append("+++ b/F").append(i).append(".java\n")
          .append("@@ -1 +1 @@\n-x\n+y\n");
    }
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(diff.toString()))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("limit is " + UnifiedDiffValidator.MAX_FILES);
  }

  @Test
  void rejectsTooManyChangedLines() {
    StringBuilder diff = new StringBuilder(
        "diff --git a/Big.java b/Big.java\n--- a/Big.java\n+++ b/Big.java\n@@ -1 +1 @@\n");
    for (int i = 0; i < UnifiedDiffValidator.MAX_CHANGED_LINES + 1; i++) {
      diff.append("+line ").append(i).append('\n');
    }
    assertThatThrownBy(() -> UnifiedDiffValidator.validate(diff.toString()))
        .isInstanceOf(DiffValidationException.class)
        .hasMessageContaining("changed lines");
  }

  private static String withPaths(String path) {
    return "diff --git a/" + path + " b/" + path + "\n"
        + "--- a/" + path + "\n"
        + "+++ b/" + path + "\n"
        + "@@ -1 +1 @@\n"
        + "+x";
  }
}
