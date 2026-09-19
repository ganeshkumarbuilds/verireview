package com.verireview.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Docker sandbox boundary for untrusted project execution (SECURITY_DESIGN
 * §4, AGENTS.md rule 14). Uploaded code, build tools, and analyzers run ONLY
 * inside a throwaway container: no network, memory/CPU/pid caps, a disposable
 * snapshot copy (never the canonical store), forced timeout, and workspace
 * wipe afterwards. The backend only ever reads report files back.
 *
 * <p>Invocation uses {@code ProcessBuilder} arg arrays — never a shell, never
 * interpolated user input (only internally generated temp paths).
 */
@Component
public class SandboxRunner {

  private static final Logger log = LoggerFactory.getLogger(SandboxRunner.class);

  private final String image;
  private final long timeoutSeconds;
  private final String memory;
  private final String cpus;
  private final String pidsLimit;

  public SandboxRunner(
      @Value("${app.analysis.image:verireview-analysis:phase6}") String image,
      @Value("${app.analysis.timeout-seconds:300}") long timeoutSeconds,
      @Value("${app.analysis.memory:2g}") String memory,
      @Value("${app.analysis.cpus:2}") String cpus,
      @Value("${app.analysis.pids-limit:256}") String pidsLimit) {
    this.image = image;
    this.timeoutSeconds = timeoutSeconds;
    this.memory = memory;
    this.cpus = cpus;
    this.pidsLimit = pidsLimit;
  }

  /** Disposable container run workspace (snapshot copy + report dir). */
  public record SandboxWorkspace(Path snapshotDir, Path outputDir) {
  }

  /**
   * Copies the canonical project tree into a disposable snapshot and runs the
   * analysis image over it. Symlinks are never followed into the container.
   *
   * @throws SandboxException on docker, timeout, or container failure
   */
  public SandboxWorkspace analyzeSnapshot(Path projectDir) throws SandboxException {
    Path work;
    try {
      work = Files.createTempDirectory("verireview-analysis-");
    } catch (IOException e) {
      throw new SandboxException("Could not stage the analysis workspace");
    }
    Path snapshot = work.resolve("src");
    Path output = work.resolve("out");
    try {
      copySnapshot(projectDir, snapshot);
      Files.createDirectories(output);
      runContainer(snapshot, output);
      return new SandboxWorkspace(snapshot, output);
    } catch (SandboxException e) {
      deleteQuietly(work);
      throw e;
    } catch (Exception e) {
      deleteQuietly(work);
      throw new SandboxException("Analysis sandbox failed: " + e.getMessage());
    }
  }

  public void deleteWorkspace(SandboxWorkspace workspace) {
    if (workspace == null) {
      return;
    }
    deleteQuietly(workspace.snapshotDir().getParent());
  }

  private void copySnapshot(Path projectDir, Path snapshot) throws IOException {
    if (projectDir == null || !Files.isDirectory(projectDir)) {
      Files.createDirectories(snapshot);
      return;
    }
    try (var stream = Files.walk(projectDir)) {
      for (Path source : (Iterable<Path>) stream::iterator) {
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source)) {
          continue;
        }
        Path target = snapshot.resolve(projectDir.relativize(source).toString());
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private void runContainer(Path snapshot, Path output) throws SandboxException {
    List<String> command = new ArrayList<>(List.of(
        "docker", "run", "--rm",
        "--network", "none",
        "--memory", memory,
        "--cpus", cpus,
        "--pids-limit", pidsLimit,
        "-v", snapshot.toAbsolutePath() + ":/src",
        "-v", output.toAbsolutePath() + ":/out",
        image, "/src", "/out"));
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      // Drain container output on a side thread: bounding waitFor() below is
      // what enforces the timeout, and the pipe must never block the run.
      java.io.ByteArrayOutputStream console = new java.io.ByteArrayOutputStream();
      Thread drainer = new Thread(() -> {
        try {
          process.getInputStream().transferTo(console);
        } catch (IOException ignored) {
          // Stream closes on destroy; nothing to report.
        }
      });
      drainer.setDaemon(true);
      drainer.start();
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        drainer.join(5000);
        throw new SandboxTimeoutException(
            "Analysis exceeded the " + timeoutSeconds + "s sandbox timeout");
      }
      drainer.join(10_000);
      String consoleText = console.toString(java.nio.charset.StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        log.warn("Analysis container exited {}: {}", process.exitValue(), tail(consoleText));
        throw new SandboxException(
            "Analysis container failed (exit " + process.exitValue() + ")");
      }
    } catch (IOException e) {
      throw new SandboxException(
          "Docker is unavailable for sandboxed analysis: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Analysis was interrupted");
    }
  }

  private static String tail(String console) {
    if (console == null || console.length() <= 2000) {
      return console;
    }
    return console.substring(console.length() - 2000);
  }

  private static void deleteQuietly(Path path) {
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
              // Best effort: cleanup must never mask the real error.
            }
          });
        }
      }
    } catch (IOException ignored) {
      // Best effort (see above).
    }
  }

  public static class SandboxException extends Exception {
    SandboxException(String message) {
      super(message);
    }
  }

  public static final class SandboxTimeoutException extends SandboxException {
    SandboxTimeoutException(String message) {
      super(message);
    }
  }
}
