package com.verireview.fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict validator for AI-proposed unified diffs (Coding Agent foundation).
 *
 * <p>The Coding Agent is read-only: it returns a diff, it never touches project
 * files. This gate runs before a {@link Patch} is persisted and rejects anything
 * that is not a tight, project-relative unified diff:
 *
 * <ul>
 *   <li>malformed diffs (missing {@code diff --git} / {@code ---} / {@code +++} / {@code @@})</li>
 *   <li>absolute paths ({@code /etc/x}, {@code C:/x}, {@code C:\x})</li>
 *   <li>traversal ({@code ..} segments, backslashes, empty segments)</li>
 *   <li>binary diffs ({@code Binary files … differ}, {@code GIT binary patch})</li>
 *   <li>paths that do not stay project-relative (old/new sides must agree,
 *       {@code /dev/null} allowed for added/removed files only)</li>
 *   <li>oversized proposals (more than {@value #MAX_FILES} files or
 *       {@value #MAX_CHANGED_LINES} changed lines)</li>
 *   <li>diffs claiming verification ({@code verified}, {@code tests passed},
 *       {@code build passed}) — verification is a backend verdict, never an AI claim</li>
 * </ul>
 *
 * <p>Pure JDK, no I/O, no DB access. Callers translate
 * {@link DiffValidationException} into a controlled failure (502, no patch
 * persisted, {@code FixRequest} stays {@code REQUESTED}).
 */
public final class UnifiedDiffValidator {

  /** Maximum number of files a single proposal may touch. */
  public static final int MAX_FILES = 5;

  /** Maximum number of added + removed lines a single proposal may contain. */
  public static final int MAX_CHANGED_LINES = 200;

  /** Maximum raw diff size in characters. */
  public static final int MAX_DIFF_CHARS = 200_000;

  private static final Pattern HUNK_HEADER =
      Pattern.compile("@@ -\\d+(,\\d+)? \\+\\d+(,\\d+)? @@.*");
  private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*");

  private UnifiedDiffValidator() {
  }

  /**
   * Validates {@code diff} and returns authoritative stats derived from the
   * diff itself (never trust AI-reported counts).
   *
   * @throws DiffValidationException if the diff violates any rule
   */
  public static DiffStats validate(String diff) {
    if (diff == null || diff.isBlank()) {
      throw new DiffValidationException("diff is empty");
    }
    String stripped = diff.strip();
    if (stripped.length() > MAX_DIFF_CHARS) {
      throw new DiffValidationException("diff exceeds 200KB limit");
    }
    String lowered = stripped.toLowerCase();
    if (lowered.contains("verified")
        || lowered.contains("tests passed")
        || lowered.contains("build passed")) {
      throw new DiffValidationException("diff must not claim verification");
    }

    List<String> lines = new ArrayList<>();
    for (String line : stripped.split("\n")) {
      lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
    }

    int first = 0;
    while (first < lines.size() && lines.get(first).isBlank()) {
      first++;
    }
    if (first >= lines.size() || !lines.get(first).startsWith("diff --git ")) {
      throw new DiffValidationException(
          "diff is not valid unified format (must start with diff --git)");
    }

    List<FileBlock> blocks = splitBlocks(lines, first);
    if (blocks.isEmpty()) {
      throw new DiffValidationException(
          "diff is not valid unified format (missing diff --git / --- / +++ / @@)");
    }
    if (blocks.size() > MAX_FILES) {
      throw new DiffValidationException(
          "diff touches " + blocks.size() + " files, limit is " + MAX_FILES);
    }

    int additions = 0;
    int deletions = 0;
    Set<String> files = new LinkedHashSet<>();
    for (FileBlock block : blocks) {
      String file = checkBlock(block);
      files.add(file);
      additions += block.additions;
      deletions += block.deletions;
    }
    if (additions + deletions > MAX_CHANGED_LINES) {
      throw new DiffValidationException(
          "diff has " + (additions + deletions) + " changed lines, limit is " + MAX_CHANGED_LINES);
    }
    return new DiffStats(
        files.size(), additions, deletions, Collections.unmodifiableList(new ArrayList<>(files)));
  }

  /** Authoritative stats derived from the diff text itself. */
  public record DiffStats(
      int filesChanged, int additions, int deletions, List<String> files) {
  }

  /** Thrown when a proposed diff violates any validation rule. */
  public static class DiffValidationException extends RuntimeException {
    public DiffValidationException(String message) {
      super(message);
    }
  }

  private static final class FileBlock {
    final String gitLine;
    final List<String> body = new ArrayList<>();
    int additions;
    int deletions;

    FileBlock(String gitLine) {
      this.gitLine = gitLine;
    }
  }

  private static List<FileBlock> splitBlocks(List<String> lines, int first) {
    List<FileBlock> blocks = new ArrayList<>();
    FileBlock current = null;
    for (int i = first; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("diff --git ")) {
        current = new FileBlock(line);
        blocks.add(current);
      } else if (current != null) {
        current.body.add(line);
        if (line.startsWith("+") && !line.startsWith("+++")) {
          current.additions++;
        } else if (line.startsWith("-") && !line.startsWith("---")) {
          current.deletions++;
        }
      }
    }
    return blocks;
  }

  private static String checkBlock(FileBlock block) {
    String[] paths = gitPaths(block.gitLine);
    String oldPath = stripPrefix(paths[0], "a/");
    String newPath = stripPrefix(paths[1], "b/");
    boolean oldNull = oldPath == null;
    boolean newNull = newPath == null;
    if (oldNull && newNull) {
      throw new DiffValidationException("diff block has no file paths: " + block.gitLine);
    }

    String oldFile = oldNull ? null : checkProjectPath(oldPath);
    String newFile = newNull ? null : checkProjectPath(newPath);

    String oldHeader = null;
    String newHeader = null;
    boolean hunk = false;
    for (String line : block.body) {
      if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
        throw new DiffValidationException("diff must not contain binary content");
      }
      if (line.startsWith("--- ")) {
        oldHeader = headerPath(line, 4);
      } else if (line.startsWith("+++ ")) {
        newHeader = headerPath(line, 4);
      } else if (line.startsWith("@@")) {
        if (!HUNK_HEADER.matcher(line).matches()) {
          throw new DiffValidationException("diff has malformed hunk header: " + line);
        }
        hunk = true;
      }
    }
    if (oldHeader == null || newHeader == null) {
      throw new DiffValidationException(
          "diff block is missing --- / +++ headers: " + block.gitLine);
    }

    // /dev/null marks added (old) / deleted (new) files; otherwise the header
    // must name the same project-relative path as the git line.
    String effectiveOld = "/dev/null".equals(oldHeader) ? null : checkHeader(oldHeader, "a/", oldFile);
    String effectiveNew = "/dev/null".equals(newHeader) ? null : checkHeader(newHeader, "b/", newFile);
    if (effectiveOld == null && effectiveNew == null) {
      throw new DiffValidationException("diff block has no file paths: " + block.gitLine);
    }
    if (effectiveOld != null && effectiveNew != null && !effectiveOld.equals(effectiveNew)) {
      throw new DiffValidationException(
          "diff renames across paths are not allowed: " + effectiveOld + " -> " + effectiveNew);
    }
    String file = effectiveOld != null ? effectiveOld : effectiveNew;
    if (!hunk) {
      throw new DiffValidationException("diff block for " + file + " has no hunks");
    }
    return file;
  }

  private static String checkHeader(String header, String prefix, String gitFile) {
    if (gitFile == null) {
      throw new DiffValidationException("diff headers do not match git paths: " + header);
    }
    String expected = prefix + gitFile;
    if (!header.equals(expected)) {
      throw new DiffValidationException(
          "diff headers do not match git paths for " + gitFile + ": " + header);
    }
    return gitFile;
  }

  private static String[] gitPaths(String gitLine) {
    String rest = gitLine.substring("diff --git ".length()).strip();
    List<String> tokens = tokenize(rest);
    if (tokens.size() != 2) {
      throw new DiffValidationException("diff has malformed git header: " + gitLine);
    }
    return new String[] {tokens.get(0), tokens.get(1)};
  }

  private static List<String> tokenize(String rest) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    boolean inToken = false;
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
        inToken = true;
      } else if (c == ' ' || c == '\t') {
        if (inQuotes) {
          current.append(c);
        } else if (inToken) {
          tokens.add(current.toString());
          current.setLength(0);
          inToken = false;
        }
      } else {
        current.append(c);
        inToken = true;
      }
    }
    if (inToken) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private static String stripPrefix(String path, String prefix) {
    if ("/dev/null".equals(path)) {
      return null;
    }
    if (!path.startsWith(prefix)) {
      throw new DiffValidationException("diff path must start with " + prefix + ": " + path);
    }
    return path.substring(prefix.length());
  }

  private static String headerPath(String line, int prefixLength) {
    String path = line.substring(prefixLength).strip();
    int tab = path.indexOf('\t');
    if (tab >= 0) {
      path = path.substring(0, tab).strip();
    }
    if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
      path = path.substring(1, path.length() - 1);
    }
    return path;
  }

  /**
   * Jail check: the path must stay project-relative. Rejects absolute paths
   * (POSIX and Windows drive), traversal segments, backslashes, empty
   * segments, and control characters. Reused by the generation reviewer so
   * AI-produced file paths obey the same jail as diffs and uploads.
   */
  public static String checkProjectPath(String path) {
    if (path == null || path.isEmpty()) {
      throw new DiffValidationException("diff contains empty path");
    }
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '\0' || (c < 0x20 && c != '\t')) {
        throw new DiffValidationException("diff contains illegal path: " + path);
      }
    }
    if (path.startsWith("/") || path.contains("\\") || DRIVE_LETTER.matcher(path).matches()) {
      throw new DiffValidationException("diff contains absolute path: " + path);
    }
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new DiffValidationException("diff contains illegal path: " + path);
      }
    }
    return path;
  }
}
