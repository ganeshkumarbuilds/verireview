package com.verireview.generation;

import com.verireview.audit.AuditService;
import com.verireview.fix.UnifiedDiffValidator;
import com.verireview.generation.dto.RestructureProposalRequest;
import com.verireview.generation.dto.RestructureProposalRequest.RestructurePreset;
import com.verireview.generation.dto.RestructureView;
import com.verireview.ingestion.ProjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Restructure my project" flow for generated workspaces.
 *
 * <p>Safety: a proposal is only computed and persisted; no file moves until
 * the user explicitly applies it. Apply first copies the whole workspace to a
 * sibling backup, then moves files inside the canonical workspace jail. Revert
 * restores that backup. Paths are never interpolated into a shell.
 */
@Service
public class GenerationRestructureService {

  private final GenerationRepository generations;
  private final GenerationArtifactRepository artifacts;
  private final GenerationRestructureRepository proposals;
  private final ProjectStorage storage;
  private final ObjectMapper objects;
  private final AuditService audits;

  public GenerationRestructureService(
      GenerationRepository generations,
      GenerationArtifactRepository artifacts,
      GenerationRestructureRepository proposals,
      ProjectStorage storage,
      ObjectMapper objects,
      AuditService audits) {
    this.generations = generations;
    this.artifacts = artifacts;
    this.proposals = proposals;
    this.storage = storage;
    this.objects = objects;
    this.audits = audits;
  }

  @Transactional
  public RestructureView propose(UUID ownerId, UUID generationId,
      RestructureProposalRequest request) {
    Generation generation = owned(ownerId, generationId);
    GenerationArtifact artifact = artifacts
        .findFirstByGenerationIdOrderByCreatedAtDesc(generationId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "Generate the project before restructuring it"));
    Path workspace = storage.generationWorkspaceDir(generationId, artifact.getIteration());
    List<String> files = workspaceFiles(workspace);
    if (files.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "No generated files are available to restructure");
    }
    Plan plan = plan(request.preset(), request.description(), files);
    if (plan.moves().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The selected structure would not move any files");
    }
    String json = encode(plan);
    GenerationRestructure row = proposals.save(new GenerationRestructure(
        generation, artifact.getIteration(),
        GenerationRestructure.Preset.valueOf(request.preset().name()),
        blankToNull(request.description()), json));
    audits.record(generation.getOwner(), "GENERATION_RESTRUCTURE_PROPOSED", "generation",
        generationId.toString());
    return toView(row, plan);
  }

  @Transactional(readOnly = true)
  public List<RestructureView> list(UUID ownerId, UUID generationId) {
    owned(ownerId, generationId);
    return proposals.findByGenerationIdOrderByCreatedAtDesc(generationId).stream()
        .map(row -> toView(row, decode(row)))
        .toList();
  }

  @Transactional
  public RestructureView apply(UUID ownerId, UUID generationId, UUID proposalId) {
    Generation generation = owned(ownerId, generationId);
    GenerationRestructure row = proposals.findByIdAndGenerationId(proposalId, generationId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Restructure proposal not found"));
    if (row.getStatus() != GenerationRestructure.Status.PROPOSED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only a proposed restructure can be applied");
    }
    Path workspace = storage.generationWorkspaceDir(generationId, row.getIteration());
    if (!Files.isDirectory(workspace)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Generated workspace is missing");
    }
    Plan plan = decode(row);
    Path backup = workspace.resolveSibling(
        "iter-" + row.getIteration() + "-backup-" + row.getId());
    try {
      copyTree(workspace, backup);
      for (Move move : plan.moves()) {
        Path source = storage.resolveJailed(workspace, move.from());
        Path target = storage.resolveJailed(workspace, move.to());
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
          throw new IOException("Source is not a regular file: " + move.from());
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (ResponseStatusException e) {
      rollback(backup, workspace, e);
      throw e;
    } catch (IOException e) {
      rollback(backup, workspace, e);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Could not apply the restructure safely. The original workspace was restored.");
    }
    row.setStatus(GenerationRestructure.Status.APPLIED);
    row.setBackupRef(backup.toAbsolutePath().normalize().toString());
    audits.record(generation.getOwner(), "GENERATION_RESTRUCTURE_APPLIED", "generation",
        generationId.toString());
    return toView(row, plan);
  }

  @Transactional
  public RestructureView revert(UUID ownerId, UUID generationId) {
    Generation generation = owned(ownerId, generationId);
    GenerationRestructure row = proposals
        .findFirstByGenerationIdAndStatusOrderByCreatedAtDesc(
            generationId, GenerationRestructure.Status.APPLIED)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "There is no applied restructure to revert"));
    if (row.getBackupRef() == null || row.getBackupRef().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This restructure has no backup to restore");
    }
    Path workspace = storage.generationWorkspaceDir(generationId, row.getIteration());
    Path generationRoot = workspace.getParent().toAbsolutePath().normalize();
    Path backup = Path.of(row.getBackupRef()).toAbsolutePath().normalize();
    // The stored backup must still point at a backup for this iteration under
    // the generation's own directory; never trust a stale or edited path.
    if (!generationRoot.equals(backup.getParent())
        || !backup.getFileName().toString()
            .startsWith("iter-" + row.getIteration() + "-backup-")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The restructure backup reference is invalid");
    }
    if (!Files.isDirectory(backup)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The restructure backup is no longer available");
    }
    try {
      // Replace, not merge: the moved files must disappear so the workspace
      // is byte-for-byte the original tree again.
      restoreTree(backup, workspace);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Could not restore the original structure. The backup was kept.");
    }
    row.setStatus(GenerationRestructure.Status.REVERTED);
    audits.record(generation.getOwner(), "GENERATION_RESTRUCTURE_REVERTED", "generation",
        generationId.toString());
    return toView(row, decode(row));
  }

  private Generation owned(UUID ownerId, UUID generationId) {
    return generations.findByIdAndOwnerId(generationId, ownerId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Generation not found"));
  }

  private List<String> workspaceFiles(Path workspace) {
    List<String> files = new ArrayList<>();
    if (!Files.isDirectory(workspace)) {
      return files;
    }
    try (var stream = Files.walk(workspace)) {
      stream.filter(Files::isRegularFile)
          .filter(path -> !Files.isSymbolicLink(path))
          .forEach(path -> files.add(workspace.relativize(path)
              .toString().replace('\\', '/')));
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Could not inspect the generated workspace");
    }
    files.sort(String::compareTo);
    return files;
  }

  private Plan plan(RestructurePreset preset, String description, List<String> files) {
    return switch (preset) {
      case MVC -> presetPlan(files, Layout.MVC);
      case LAYERED -> presetPlan(files, Layout.LAYERED);
      case FEATURE_FOLDERS -> presetPlan(files, Layout.FEATURE_FOLDERS);
      case CUSTOM -> customPlan(description, files);
    };
  }

  private Plan presetPlan(List<String> files, Layout layout) {
    List<Move> moves = new ArrayList<>();
    Map<String, String> taken = new LinkedHashMap<>();
    for (String file : files) {
      if (!eligible(file)) {
        continue;
      }
      String target = layout.target(file);
      if (target == null || target.equals(file)) {
        continue;
      }
      addMove(moves, taken, file, target);
    }
    return new Plan(moves, directories(moves),
        layout == Layout.MVC
            ? "MVC: controllers, services, repositories, models. Config and docs stay put."
            : layout == Layout.LAYERED
                ? "Layered: presentation, domain, infrastructure, tests. Config and docs stay put."
                : "Feature folders: one folder per feature, shared core under features/core.");
  }

  /**
   * Custom parser. Supported lines:
   * {@code target/: path1, path2} — also accepts {@code ->} or {@code <-}.
   * Unmatched lines are reported in the notes; preset keywords still work.
   */
  private Plan customPlan(String description, List<String> files) {
    String text = description == null ? "" : description;
    if (text.isBlank()) {
      return presetPlan(files, Layout.FEATURE_FOLDERS);
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("mvc") && !lower.contains(":")) {
      return presetPlan(files, Layout.MVC);
    }
    if (lower.contains("layered") && !lower.contains(":")) {
      return presetPlan(files, Layout.LAYERED);
    }
    List<Move> moves = new ArrayList<>();
    Map<String, String> taken = new LinkedHashMap<>();
    List<String> unmatched = new ArrayList<>();
    for (String rawLine : text.split("\\R")) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = line.split("(->|<-|:)", 2);
      if (parts.length != 2) {
        unmatched.add(line);
        continue;
      }
      String folder = parts[0].trim().replaceAll("/+$", "");
      if (folder.isBlank()) {
        unmatched.add(line);
        continue;
      }
      checkPath(folder);
      for (String wanted : parts[1].split(",")) {
        String needle = wanted.trim();
        if (needle.isEmpty()) {
          continue;
        }
        String match = find(files, needle);
        if (match == null) {
          unmatched.add(needle);
          continue;
        }
        String basename = match.substring(match.lastIndexOf('/') + 1);
        addMove(moves, taken, match, folder + "/" + basename);
      }
    }
    if (moves.isEmpty()) {
      Plan fallback = presetPlan(files, Layout.FEATURE_FOLDERS);
      return new Plan(fallback.moves(), fallback.directories(),
          "No folder mappings could be parsed from the description, so feature folders were "
              + "proposed. Use lines like 'services/: UserService.java'.");
    }
    String note = "Custom mapping applied.";
    if (!unmatched.isEmpty()) {
      note += " Not matched: " + String.join(", ", unmatched) + ".";
    }
    return new Plan(moves, directories(moves), note);
  }

  private static String find(List<String> files, String needle) {
    for (String file : files) {
      if (file.equals(needle) || file.endsWith("/" + needle)
          || file.substring(file.lastIndexOf('/') + 1).equals(needle)) {
        return file;
      }
    }
    return null;
  }

  private static void addMove(List<Move> moves, Map<String, String> taken,
      String from, String to) {
    checkPath(to);
    String existing = taken.putIfAbsent(to, from);
    if (existing != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Two files would move to the same path: " + to);
    }
    moves.add(new Move(from, to));
  }

  private static void checkPath(String path) {
    try {
      UnifiedDiffValidator.checkProjectPath(path);
    } catch (UnifiedDiffValidator.DiffValidationException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Illegal restructure path: " + path);
    }
  }

  private static boolean eligible(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.equals("readme.md") || lower.equals(".env.example")
        || lower.equals("pom.xml") || lower.equals("package.json")
        || lower.equals("requirements.txt") || lower.equals("dockerfile")) {
      return false;
    }
    if (lower.startsWith(".github/") || lower.startsWith("db/")
        || lower.startsWith("frontend/") || lower.startsWith("auth/")
        || lower.startsWith("integrations/")) {
      return false;
    }
    return lower.startsWith("src/") || lower.startsWith("app/");
  }

  private static String basename(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String stem(String path) {
    String name = basename(path);
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private static String feature(String path) {
    String name = stem(path).toLowerCase(Locale.ROOT);
    for (String suffix : List.of("controller", "service", "repository", "dao", "model",
        "entity", "route", "handler", "resource", "test")) {
      if (name.endsWith(suffix) && name.length() > suffix.length()) {
        name = name.substring(0, name.length() - suffix.length());
        break;
      }
    }
    name = name.replaceAll("[^a-z0-9]+", "");
    if (name.isBlank() || List.of("main", "app", "application", "index", "health")
        .contains(name)) {
      return "core";
    }
    return name;
  }

  private static List<String> directories(List<Move> moves) {
    Set<String> dirs = new LinkedHashSet<>();
    for (Move move : moves) {
      int slash = move.to().lastIndexOf('/');
      if (slash > 0) {
        dirs.add(move.to().substring(0, slash));
      }
    }
    return List.copyOf(dirs);
  }

  private String encode(Plan plan) {
    try {
      Map<String, Object> root = new LinkedHashMap<>();
      List<Object> moves = new ArrayList<>();
      for (Move move : plan.moves()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("from", move.from());
        item.put("to", move.to());
        moves.add(item);
      }
      root.put("moves", moves);
      root.put("directories", plan.directories());
      root.put("notes", plan.notes());
      return objects.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode restructure proposal", e);
    }
  }

  private Plan decode(GenerationRestructure row) {
    try {
      JsonNode root = objects.readTree(row.getProposalJson());
      List<Move> moves = new ArrayList<>();
      JsonNode items = root.get("moves");
      if (items != null && items.isArray()) {
        for (JsonNode item : items) {
          moves.add(new Move(item.get("from").asText(), item.get("to").asText()));
        }
      }
      List<String> dirs = new ArrayList<>();
      JsonNode dirItems = root.get("directories");
      if (dirItems != null && dirItems.isArray()) {
        for (JsonNode item : dirItems) {
          dirs.add(item.asText());
        }
      }
      String notes = root.get("notes") == null ? "" : root.get("notes").asText();
      return new Plan(moves, dirs, notes);
    } catch (Exception e) {
      throw new IllegalStateException("Could not read restructure proposal", e);
    }
  }

  private RestructureView toView(GenerationRestructure row, Plan plan) {
    List<RestructureView.Move> moves = new ArrayList<>();
    for (Move move : plan.moves()) {
      moves.add(new RestructureView.Move(move.from(), move.to()));
    }
    return new RestructureView(
        row.getId(),
        row.getGeneration().getId(),
        row.getIteration(),
        row.getPreset().name(),
        row.getDescription(),
        row.getStatus().name(),
        List.copyOf(moves),
        List.copyOf(plan.directories()),
        plan.notes(),
        row.getStatus() == GenerationRestructure.Status.APPLIED,
        row.getCreatedAt());
  }

  /**
   * Restores the workspace from a known-good backup. The current tree is
   * parked beside the workspace first; if copying fails, the parked tree is
   * put back so a failed revert never loses the user's files.
   */
  private void restoreTree(Path backup, Path workspace) throws IOException {
    Path parked = workspace.resolveSibling(
        workspace.getFileName() + "-replaced-" + UUID.randomUUID());
    boolean moved = false;
    if (Files.exists(workspace)) {
      Files.move(workspace, parked);
      moved = true;
    }
    try {
      copyTree(backup, workspace);
    } catch (IOException e) {
      storage.deleteQuietly(workspace);
      if (moved) {
        try {
          Files.move(parked, workspace);
        } catch (IOException restoreError) {
          e.addSuppressed(restoreError);
        }
      }
      throw e;
    }
    if (moved) {
      storage.deleteQuietly(parked);
    }
  }

  /** Best-effort rollback after a failed apply; the cause keeps any details. */
  private void rollback(Path backup, Path workspace, Exception cause) {
    try {
      restoreTree(backup, workspace);
      storage.deleteQuietly(backup);
    } catch (IOException e) {
      cause.addSuppressed(e);
      // Keep the backup when restoration failed so an operator can recover.
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      throw new IOException("Source directory is missing");
    }
    try (var stream = Files.walk(source)) {
      for (Path path : (Iterable<Path>) stream::iterator) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
          continue;
        }
        Path destination = target.resolve(source.relativize(path).toString());
        Files.createDirectories(destination.getParent());
        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  record Plan(List<Move> moves, List<String> directories, String notes) {
  }

  record Move(String from, String to) {
  }

  private enum Layout {
    MVC {
      @Override
      String target(String file) {
        String name = basename(file);
        String lower = name.toLowerCase(Locale.ROOT);
        if (file.startsWith("src/test/") || lower.contains("test")) {
          return "tests/" + name;
        }
        if (lower.contains("controller")) {
          return "controllers/" + name;
        }
        if (lower.contains("service")) {
          return "services/" + name;
        }
        if (lower.contains("repository") || lower.contains("dao")) {
          return "repositories/" + name;
        }
        if (lower.contains("model") || lower.contains("entity") || lower.contains("dto")) {
          return "models/" + name;
        }
        return "views/" + name;
      }
    },
    LAYERED {
      @Override
      String target(String file) {
        String name = basename(file);
        String lower = name.toLowerCase(Locale.ROOT);
        if (file.startsWith("src/test/") || lower.contains("test")) {
          return "tests/" + name;
        }
        if (lower.contains("controller") || lower.contains("route")
            || lower.contains("handler") || lower.contains("resource")) {
          return "presentation/" + name;
        }
        if (lower.contains("repository") || lower.contains("dao")
            || lower.contains("client") || lower.contains("config")) {
          return "infrastructure/" + name;
        }
        return "domain/" + name;
      }
    },
    FEATURE_FOLDERS {
      @Override
      String target(String file) {
        String name = basename(file);
        if (file.startsWith("src/test/") || name.toLowerCase(Locale.ROOT).contains("test")) {
          return "tests/" + name;
        }
        return "features/" + feature(file) + "/" + name;
      }
    };

    abstract String target(String file);
  }
}
