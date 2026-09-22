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
 * S4, AGENTS.md rule 14). Uploaded code, build tools, and analyzers run ONLY
 * inside a throwaway container: no network, memory/CPU/pid caps, a disposable
 * snapshot copy (never the canonical store), forced timeout, and workspace
 * wipe afterwards. The backend only ever reads report files back.
 *
 * <p>Invocation uses {@code ProcessBuilder} arg arrays -- never a shell, never
 * interpolated user input (only internally generated temp paths).
 *
 * <p>{@code app.sandbox.docker-enabled} (default true) gates every Docker
 * invocation in this class. Some deployment hosts (a standard PaaS web
 * service without Docker-in-Docker support) cannot run Docker at all; on
 * those hosts this flag is set to false so callers get an explicit, clearly
 * labeled "skipped" result instead of a crash. A skip is never reported as a
 * pass: callers can tell from {@link ExecutionResult#skipped()} that no real
 * build or analysis ran, and every skip is logged so it is visible in
 * server logs, not just swallowed silently.
 */
@Component
public class SandboxRunner {

  private static final Logger log = LoggerFactory.getLogger(SandboxRunner.class);

  private final String image;
  private final long timeoutSeconds;
  private final String memory;
  private final String cpus;
  private final String pidsLimit;
  private final String executionImage;
  private final long executionTimeoutSeconds;
  private final long maxOutputBytes;
  private final boolean dockerEnabled;

  public long getExecutionTimeoutSeconds() {
    return executionTimeoutSeconds;
  }

  /** True when Docker is available in this environment and sandbox runs are real. */
  public boolean isDockerEnabled() {
    return dockerEnabled;
  }

  public SandboxRunner(
      @Value("${app.analysis.image:verireview-analysis:phase6}") String image,
      @Value("${app.analysis.timeout-seconds:300}") long timeoutSeconds,
      @Value("${app.analysis.memory:2g}") String memory,
      @Value("${app.analysis.cpus:2}") String cpus,
      @Value("${app.analysis.pids-limit:256}") String pidsLimit,
      @Value("${app.execution.image:maven:3.9.9-eclipse-temurin-21}") String executionImage,
      @Value("${app.execution.timeout-seconds:300}") long executionTimeoutSeconds,
      @Value("${app.execution.max-output-bytes:1048576}") long maxOutputBytes,
      @Value("${app.sandbox.docker-enabled:true}") boolean dockerEnabled) {
    this.image = image;
    this.timeoutSeconds = timeoutSeconds;
    this.memory = memory;
    this.cpus = cpus;
    this.pidsLimit = pidsLimit;
    this.executionImage = executionImage;
    this.executionTimeoutSeconds = executionTimeoutSeconds;
    this.maxOutputBytes = maxOutputBytes;
    this.dockerEnabled = dockerEnabled;
    if (!dockerEnabled) {
      log.warn("Docker sandbox is DISABLED (app.sandbox.docker-enabled=false). "
          + "Builds, tests, and deterministic analysis tools will be skipped, "
          + "not actually run, in this environment.");
    }
  }

  /** Disposable container run workspace (snapshot copy + report dir). */
  public record SandboxWorkspace(Path snapshotDir, Path outputDir) {
  }

  /**
   * Result of a sandboxed build+test execution.
   *
   * @param skipped true when Docker was disabled and no container actually
   *     ran -- exitCode/stdout/stderr describe the skip, not a real result.
   */
  public record ExecutionResult(
      int exitCode, String stdout, String stderr, long durationMs, boolean timedOut, boolean skipped) {

    /** Convenience constructor for real (non-skipped) results. */
    public ExecutionResult(int exitCode, String stdout, String stderr, long durationMs, boolean timedOut) {
      this(exitCode, stdout, stderr, durationMs, timedOut, false);
    }
  }

  /**
   * Copies the canonical project tree into a disposable snapshot and runs the
   * analysis image over it. Symlinks are never followed into the container.
   *
   * <p>When {@code app.sandbox.docker-enabled} is false, returns a workspace
   * with an empty output directory instead of invoking Docker. Callers that
   * expect analyzer report files (e.g. {@code AnalysisRunner}) must check
   * {@link #isDockerEnabled()} first and treat a disabled sandbox as "no
   * deterministic tools ran," not as an empty-but-valid report set.
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
      if (!dockerEnabled) {
        log.warn("Skipping sandboxed analysis: Docker is disabled in this environment");
        return new SandboxWorkspace(snapshot, output);
      }
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

  /**
   * Executes build+test for an APPLIED project snapshot inside Docker.
   * Never runs on host; no network; capped resources; bounded timeout.
   */
  public ExecutionResult executeBuild(Path projectDir) {
    return executeBuild(projectDir, null);
  }

  /**
   * Executes a custom build command for a generation workspace inside Docker.
   * Never runs on host; no network; capped resources; bounded timeout.
   *
   * <p>When {@code app.sandbox.docker-enabled} is false, returns a skipped
   * result (exit code 0, {@link ExecutionResult#skipped()} true) without
   * touching Docker at all, so this environment can complete the generation
   * pipeline without a real build/test verification.
   *
   * @param projectDir the project/workspace directory to copy into the container
   * @param customCommand the shell command to run inside the container (e.g., "mvn test", "npm test", "pytest")
   *                      If null, defaults to Maven/Gradle detection
   */
  public ExecutionResult executeBuild(Path projectDir, String customCommand) {
    if (!dockerEnabled) {
      log.warn("Skipping sandboxed build/test: Docker is disabled in this environment");
      return new ExecutionResult(
          0,
          "Docker sandbox is disabled in this environment; build/test execution was skipped, "
              + "not run. This result does not verify that the project builds or its tests pass.",
          "",
          0,
          false,
          true);
    }
    Path work;
    try {
      work = Files.createTempDirectory("verireview-execution-");
    } catch (IOException e) {
      throw new SandboxException("Could not stage execution workspace");
    }
    Path snapshot = work.resolve("project");
    try {
      try {
        copySnapshot(projectDir, snapshot);
      } catch (IOException e) {
        throw new SandboxException("Could not stage execution workspace: " + e.getMessage());
      }
      long start = System.currentTimeMillis();
      ExecutionResult result = runExecutionContainer(snapshot, customCommand);
      long duration = System.currentTimeMillis() - start;
      return new ExecutionResult(result.exitCode(), result.stdout(), result.stderr(), duration, result.timedOut());
    } finally {
      deleteQuietly(work);
    }
  }

  private ExecutionResult runExecutionContainer(Path snapshot, String customCommand) throws SandboxException {
    String shellCommand;
    if (customCommand != null && !customCommand.isBlank()) {
      shellCommand = "cd /project && " + customCommand;
    } else {
      shellCommand = "cd /project && if [ -f pom.xml ]; then mvn -B test -o 2>&1 || mvn -B test 2>&1; elif [ -f build.gradle ]; then gradle test 2>&1; elif [ -f build.gradle.kts ]; then gradle test 2>&1; else echo 'no build file found' >&2; exit 1; fi";
    }
    List<String> command = new ArrayList<>(List.of(
        "docker", "run", "--rm",
        "--network", "none",
        "--memory", memory,
        "--cpus", cpus,
        "--pids-limit", pidsLimit,
        "-v", snapshot.toAbsolutePath() + ":/project:ro",
        executionImage,
        "sh", "-c", shellCommand));
    Process process;
    long start = System.currentTimeMillis();
    try {
      process = new ProcessBuilder(command).start();
      java.io.ByteArrayOutputStream stdoutBuf = new java.io.ByteArrayOutputStream();
      java.io.ByteArrayOutputStream stderrBuf = new java.io.ByteArrayOutputStream();
      Thread outDrainer = new Thread(() -> {
        try { process.getInputStream().transferTo(stdoutBuf); } catch (IOException ignored) {}
      });
      Thread errDrainer = new Thread(() -> {
        try { process.getErrorStream().transferTo(stderrBuf); } catch (IOException ignored) {}
      });
      outDrainer.setDaemon(true);
      errDrainer.setDaemon(true);
      outDrainer.start();
      errDrainer.start();
      boolean finished = process.waitFor(executionTimeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        outDrainer.join(5000);
        errDrainer.join(5000);
        long duration = System.currentTimeMillis() - start;
        String stdout = truncate(new String(stdoutBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        String stderr = truncate(new String(stderrBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        return new ExecutionResult(124, stdout, stderr, duration, true);
      }
      outDrainer.join(10_000);
      errDrainer.join(10_000);
      long duration = System.currentTimeMillis() - start;
      int exitCode = process.exitValue();
      String stdout = truncate(new String(stdoutBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
      String stderr = truncate(new String(stderrBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
      return new ExecutionResult(exitCode, stdout, stderr, duration, false);
    } catch (IOException e) {
      throw new SandboxException("Docker unavailable for execution: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Execution interrupted");
    }
  }

  private String truncate(String content) {
    if (content == null) return null;
    if (content.length() <= maxOutputBytes) return content;
    return content.substring(0, (int) maxOutputBytes) + "\n...[truncated at " + maxOutputBytes + " bytes]";
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

  public static class SandboxException extends RuntimeException {
    public SandboxException(String message) {
      super(message);
    }
  }

  public static final class SandboxTimeoutException extends SandboxException {
    public SandboxTimeoutException(String message) {
      super(message);
    }
  }
}