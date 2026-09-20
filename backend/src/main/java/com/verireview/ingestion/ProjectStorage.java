package com.verireview.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Filesystem side of ingestion (SECURITY_DESIGN §3). Project blobs live under
 * {@code <storage-root>/<project-id>/}; the database keeps metadata only.
 * Every path handed out or accepted is jailed to its project directory:
 * absolute paths, drive letters, and {@code ..} escapes are rejected, and no
 * path component may be a symlink (ZipSlip / symlink-escape defense).
 */
@Component
public class ProjectStorage {

  static final long FILE_VIEW_BYTES = 256L * 1024L;
  static final int BINARY_PROBE_BYTES = 8192;

  private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*");

  private final Path root;

  public ProjectStorage(@Value("${app.ingestion.storage-root:./uploads/projects}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  /** Creates the storage root on demand (idempotent). */
  public void init() throws IOException {
    Files.createDirectories(root);
  }

  /** Fresh quarantine directory for one upload; deleted on any failure. */
  public Path quarantineDir() throws IOException {
    init();
    return Files.createDirectories(root.resolve("_quarantine").resolve(UUID.randomUUID().toString()));
  }

  public Path projectDir(UUID projectId) {
    return root.resolve(projectId.toString());
  }

  /**
   * Isolated per-iteration workspace for generated files. Lives beside (never
   * inside) project directories so unreviewed output cannot leak into the
   * Review workflow; callers still jail every file with
   * {@link #resolveJailed(Path, String)} and clean up via
   * {@link #deleteQuietly(Path)}.
   */
  public Path generationWorkspaceDir(UUID generationId, int iteration) {
    return root.resolve("_generations").resolve(generationId.toString()).resolve("iter-" + iteration);
  }

  /** Moves a validated quarantine tree into its final project directory. */
  public Path moveToProject(Path quarantine, UUID projectId) throws IOException {
    Path target = projectDir(projectId);
    Files.createDirectories(target.getParent());
    Files.move(quarantine, target, StandardCopyOption.ATOMIC_MOVE);
    return target.toAbsolutePath().normalize();
  }

  public void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      if (Files.exists(path)) {
        try (var stream = Files.walk(path)) {
          stream.sorted(Comparator.reverseOrder()).forEach(entry -> {
            try {
              Files.deleteIfExists(entry);
            } catch (IOException ignored) {
              // Best effort: quarantine cleanup must never mask the real error.
            }
          });
        }
      }
    } catch (IOException ignored) {
      // Best effort (see above).
    }
  }

  /**
   * Resolves a user- or archive-supplied relative path inside {@code base}.
   *
   * @throws ResponseStatusException 400 when the path escapes the jail
   */
  public Path resolveJailed(Path base, String supplied) {
    if (supplied == null || supplied.isBlank()) {
      throw badPath(supplied);
    }
    String unified = supplied.replace('\\', '/');
    if (unified.startsWith("/") || DRIVE_LETTER.matcher(unified).matches()) {
      throw badPath(supplied);
    }
    Path normalizedBase = base.toAbsolutePath().normalize();
    Path current = normalizedBase;
    for (String part : unified.split("/")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        current = current.getParent();
        if (current == null || !current.startsWith(normalizedBase)) {
          throw badPath(supplied);
        }
        continue;
      }
      current = current.resolve(part);
      try {
        if (Files.isSymbolicLink(current)) {
          throw badPath(supplied);
        }
      } catch (SecurityException e) {
        throw badPath(supplied);
      }
      if (!current.startsWith(normalizedBase)) {
        throw badPath(supplied);
      }
    }
    if (current.equals(normalizedBase)) {
      throw badPath(supplied);
    }
    return current;
  }

  /** Capped, binary-refusing read for the file viewer. */
  public ReadResult readCapped(Path projectDir, String relativePath) throws IOException {
    Path file = resolveJailed(projectDir, relativePath);
    if (!Files.isRegularFile(file)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
    }
    long size = Files.size(file);
    byte[] probe;
    try (InputStream in = Files.newInputStream(file)) {
      probe = in.readNBytes(BINARY_PROBE_BYTES);
    }
    if (isBinary(probe)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Binary files cannot be previewed");
    }
    byte[] content;
    boolean truncated = false;
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buf = in.readNBytes((int) Math.min(size, FILE_VIEW_BYTES) + 1);
      if (buf.length > FILE_VIEW_BYTES) {
        truncated = true;
        content = java.util.Arrays.copyOf(buf, (int) FILE_VIEW_BYTES);
      } else {
        content = buf;
      }
    }
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
          .decode(java.nio.ByteBuffer.wrap(content))
          .toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "File cannot be decoded as text");
    }
    return new ReadResult(size, truncated, text);
  }

  public record ReadResult(long sizeBytes, boolean truncated, String content) {
  }

  static boolean isBinary(byte[] sample) {
    for (byte b : sample) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }

  private static ResponseStatusException badPath(String supplied) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: " + supplied);
  }
}
