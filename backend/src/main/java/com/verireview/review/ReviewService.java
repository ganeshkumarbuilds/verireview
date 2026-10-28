package com.verireview.review;

import com.verireview.common.PagedResponse;
import com.verireview.analysis.AnalysisCheck;
import com.verireview.analysis.AnalysisCheckRepository;
import com.verireview.analysis.AnalysisFileResult;
import com.verireview.analysis.AnalysisFileResultRepository;
import com.verireview.generation.GenerationRatingCalculator;
import com.verireview.generation.dto.GenerationResponse;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectFileRepository;
import com.verireview.review.dto.DashboardStatsResponse;
import com.verireview.review.dto.FindingResponse;
import com.verireview.review.dto.ReviewResponse;
import com.verireview.review.dto.RetryReviewRequest;
import com.verireview.review.dto.ReviewFixPreviewResponse;
import com.verireview.analysis.AnalysisJobService;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Review reads behind the auth boundary. Reviews are project children, so
 * every lookup re-checks project ownership (404 for foreign rows).
 */
@Service
public class ReviewService {

  private final ProjectRepository projects;
  private final ReviewRepository reviews;
  private final FindingRepository findings;
  private final ObjectMapper objects;
  private final AnalysisCheckRepository checks;
  private final ProjectFileRepository projectFiles;
  private final AnalysisFileResultRepository fileResults;
  private final AnalysisJobService jobs;

  public ReviewService(
      ProjectRepository projects, ReviewRepository reviews, FindingRepository findings,
      ObjectMapper objects, AnalysisCheckRepository checks, ProjectFileRepository projectFiles,
      AnalysisFileResultRepository fileResults,
      AnalysisJobService jobs) {
    this.projects = projects;
    this.reviews = reviews;
    this.findings = findings;
    this.objects = objects;
    this.checks = checks;
    this.projectFiles = projectFiles;
    this.fileResults = fileResults;
    this.jobs = jobs;
  }

  @Transactional(readOnly = true)
  public DashboardStatsResponse getDashboardStats(UUID ownerId, UUID projectId) {
    Project project = owned(ownerId, projectId);
    List<com.verireview.review.Review> projectReviews = reviews.findByProjectIdOrderByCreatedAtDesc(project.getId());
    com.verireview.review.Review latestReview = projectReviews.isEmpty() ? null : projectReviews.get(0);
    long totalReviews = projectReviews.size();

    long totalFindings = findings.countByReviewProjectId(project.getId());
    long openFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.OPEN);
    long fixedFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.VERIFIED_FIXED);
    long verifiedFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.VERIFIED_FIXED);
    long rejectedFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.REJECTED);
    long wontfixFindings = findings.countByReviewProjectIdAndStatus(project.getId(), FindingStatus.WONTFIX);
    long criticalHighFindings = findings.countByReviewProjectIdAndSeverityIn(project.getId(), List.of(FindingSeverity.CRITICAL, FindingSeverity.HIGH));
    long deterministicFindings = findings.countByReviewProjectIdAndSource(project.getId(), FindingSource.DETERMINISTIC);
    long aiFindings = findings.countByReviewProjectIdAndSource(project.getId(), FindingSource.AI);

    return new DashboardStatsResponse(
        project.getId(),
        project.getName(),
        totalFindings,
        openFindings,
        fixedFindings,
        verifiedFindings,
        rejectedFindings,
        wontfixFindings,
        criticalHighFindings,
        deterministicFindings,
        aiFindings,
        latestReview != null ? latestReview.getId().hashCode() : 0,
        latestReview != null ? latestReview.getStatus().toString() : "NONE",
        totalReviews
    );
  }

  @Transactional(readOnly = true)
  public List<DashboardStatsResponse> getAllDashboardStats(UUID ownerId) {
    List<Project> userProjects = projects.findByOwnerIdAndDeletedAtIsNull(ownerId);
    return userProjects.stream()
        .map(project -> getDashboardStats(ownerId, project.getId()))
        .toList();
  }

  @Transactional(readOnly = true)
  public PagedResponse<ReviewResponse> history(UUID ownerId, UUID projectId, Pageable pageable) {
    Project project = owned(ownerId, projectId);
    Page<Review> page = reviews.findByProjectIdOrderByCreatedAtDesc(project.getId(), pageable);
    return PagedResponse.of(page.map(this::toResponse));
  }

  @Transactional(readOnly = true)
  public ReviewResponse get(UUID ownerId, UUID reviewId) {
    return toResponse(ownedReview(ownerId, reviewId));
  }

  public void assertOwned(UUID ownerId, UUID reviewId) {
    ownedReview(ownerId, reviewId);
  }

  @Transactional
  public ReviewResponse retry(UUID ownerId, UUID reviewId, RetryReviewRequest request) {
    Review review = ownedReview(ownerId, reviewId);
    if (request == null || request.stage() == null
        || !java.util.Set.of("analyze", "review", "rating").contains(request.stage().toLowerCase())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid review stage to retry");
    }
    if (reviews.existsByProjectIdAndStatusIn(review.getProject().getId(),
        List.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "An analysis is already running");
    }
    jobs.retry(review, request);
    return toResponse(review);
  }

  @Transactional(readOnly = true)
  public PagedResponse<FindingResponse> findings(
      UUID ownerId, UUID reviewId, FindingSeverity severity, FindingCategory category,
      FindingStatus status, FindingSource source, Boolean toolConfirmed, Pageable pageable) {
    Review review = ownedReview(ownerId, reviewId);
    Page<Finding> page =
        findings.search(review.getId(), severity, category, status, source, toolConfirmed, pageable);
    return PagedResponse.of(page.map(this::toFindingResponseInstance));
  }

  @Transactional(readOnly = true)
  public ReviewFixPreviewResponse previewFixFiles(
      UUID ownerId, UUID reviewId, List<UUID> findingIds) {
    Review review = ownedReview(ownerId, reviewId);
    if (findingIds == null || findingIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one finding");
    }
    List<Finding> selected = findings.findAllById(findingIds);
    if (selected.size() != findingIds.size()
        || selected.stream().anyMatch(f -> !f.getReview().getId().equals(review.getId()))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected findings are not in this review");
    }
    java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
    List<com.verireview.project.ProjectFile> projectFileRows =
        projectFiles.findByProjectId(review.getProject().getId());
    for (Finding finding : selected) {
      if (finding.getFilePath() != null && !finding.getFilePath().isBlank()) {
        String selectedPath = finding.getFilePath();
        paths.add(selectedPath);
        projectFileRows.stream()
            .filter(file -> !file.getPath().equals(selectedPath)
                && references(file.getPath(), file.getContent(), selectedPath))
            .map(com.verireview.project.ProjectFile::getPath)
            .forEach(paths::add);
      }
    }
    return new ReviewFixPreviewResponse(List.copyOf(paths), selected.size());
  }

  static boolean references(String dependentPath, String content, String targetPath) {
    if (content == null || content.isBlank() || targetPath == null || targetPath.isBlank()) {
      return false;
    }
    String normalizedTarget = targetPath.replace('\\', '/');
    String normalizedDep = dependentPath.replace('\\', '/');
    String targetBase = normalizedTarget.substring(normalizedTarget.lastIndexOf('/') + 1);
    int dot = targetBase.lastIndexOf('.');
    String stem = dot > 0 ? targetBase.substring(0, dot) : targetBase;
    String dependent = normalizedDep.toLowerCase();

    if (dependent.endsWith(".java")) {
      return referencesJava(content, normalizedTarget, stem);
    }
    if (dependent.endsWith(".ts") || dependent.endsWith(".tsx")
        || dependent.endsWith(".js") || dependent.endsWith(".jsx")
        || dependent.endsWith(".mjs") || dependent.endsWith(".cjs")) {
      return referencesJsTs(normalizedDep, content, normalizedTarget, stem);
    }
    if (dependent.endsWith(".py")) {
      return referencesPython(content, normalizedTarget, stem, targetBase);
    }
    return content.toLowerCase().contains(targetBase.toLowerCase());
  }

  private static boolean referencesJava(String content, String normalizedTarget, String stem) {
    // 1. Direct or static import: import ...UserService; or import static ...UserService.xxx;
    Pattern directImport = Pattern.compile(
        "(?m)^\\s*import\\s+(?:static\\s+)?(?:[\\w.]+\\.)?" + Pattern.quote(stem) + "(?:\\.[\\w*]+)?\\s*;");
    if (directImport.matcher(content).find()) {
      return true;
    }

    // 2. Wildcard import matching target package (strip common prefixes src/main/java/, src/test/java/, src/, java/)
    String pkgPath = normalizedTarget.replaceAll("^(?:src/(?:main|test)/java/|src/|java/)", "");
    int lastSlash = pkgPath.lastIndexOf('/');
    if (lastSlash > 0) {
      String pkg = pkgPath.substring(0, lastSlash).replace('/', '.');
      Pattern wildcard = Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(pkg) + "\\.\\*\\s*;");
      if (wildcard.matcher(content).find()) {
        return true;
      }
    }

    // 3. Fallback: word boundary match for stem in Java content
    return Pattern.compile("(?s).*\\b" + Pattern.quote(stem) + "\\b.*").matcher(content).matches();
  }

  private static boolean referencesJsTs(
      String dependentPath, String content, String normalizedTarget, String stem) {
    String relative = relativeModule(dependentPath, normalizedTarget);
    String lower = content.toLowerCase();

    // 1. Fast substring checks for relative specifier
    if (lower.contains(("from '" + relative).toLowerCase())
        || lower.contains(("from \"" + relative).toLowerCase())
        || lower.contains(("require('" + relative).toLowerCase())
        || lower.contains(("require(\"" + relative).toLowerCase())) {
      return true;
    }

    // 2. Regex matching import / require / export from / include specifiers
    Pattern specifierPattern = Pattern.compile(
        "(?:from\\s+|require\\s*\\(\\s*|import\\s*\\(\\s*|import\\s+|export\\s+.*?\\s+from\\s+|export\\s*\\*\\s*from\\s+|include\\s*)['\"]([^'\"]+)['\"]");
    Matcher matcher = specifierPattern.matcher(content);
    while (matcher.find()) {
      String spec = matcher.group(1).trim().replace('\\', '/');
      String strippedSpec = spec.replaceAll("\\.(?:tsx?|jsx?|mjs|cjs)$", "");
      if (strippedSpec.equals(relative)
          || spec.equals(relative)
          || strippedSpec.equals("./" + stem)
          || strippedSpec.endsWith("/" + stem)
          || strippedSpec.equals(stem)) {
        return true;
      }
      if (normalizedTarget.endsWith("/index.ts") || normalizedTarget.endsWith("/index.js")
          || normalizedTarget.endsWith("/index.tsx") || normalizedTarget.endsWith("/index.jsx")) {
        String dirRelative = relative.replaceAll("/index$", "");
        if (strippedSpec.equals(dirRelative)) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean referencesPython(
      String content, String normalizedTarget, String stem, String targetBase) {
    // 1. from <module> import <names>
    Pattern fromPattern = Pattern.compile("(?m)^\\s*from\\s+([.\\w]+)\\s+import\\s+([^\\n#]+)");
    Matcher fromMatcher = fromPattern.matcher(content);
    while (fromMatcher.find()) {
      String mod = fromMatcher.group(1);
      String names = fromMatcher.group(2);
      if (mod.equals(stem) || mod.endsWith("." + stem)) {
        return true;
      }
      if (Pattern.compile("\\b" + Pattern.quote(stem) + "\\b").matcher(names).find()) {
        return true;
      }
    }

    // 2. import <modules>
    Pattern importPattern = Pattern.compile("(?m)^\\s*import\\s+([^\\n#]+)");
    Matcher importMatcher = importPattern.matcher(content);
    while (importMatcher.find()) {
      String line = importMatcher.group(1);
      if (Pattern.compile("\\b" + Pattern.quote(stem) + "\\b").matcher(line).find()) {
        return true;
      }
    }

    // 3. Dynamic import: __import__('stem') or import_module('stem')
    Pattern dynamicPattern = Pattern.compile(
        "(?:__import__|import_module)\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    Matcher dynamicMatcher = dynamicPattern.matcher(content);
    while (dynamicMatcher.find()) {
      String mod = dynamicMatcher.group(1);
      if (mod.equals(stem) || mod.endsWith("." + stem)) {
        return true;
      }
    }

    // 4. include statement
    Pattern includePattern = Pattern.compile("(?m)^\\s*include\\s+['\"]?([^\\s'\"#]+)['\"]?");
    Matcher includeMatcher = includePattern.matcher(content);
    while (includeMatcher.find()) {
      String inc = includeMatcher.group(1);
      if (inc.equals(stem) || inc.equals(targetBase) || inc.endsWith("/" + targetBase)) {
        return true;
      }
    }

    // 5. Fallback: module name substring
    String fullModule = normalizedTarget.replace('/', '.').replaceAll("\\.py$", "");
    String lower = content.toLowerCase();
    return lower.contains(("import " + fullModule).toLowerCase())
        || lower.contains(("from " + fullModule).toLowerCase())
        || lower.contains(("import " + stem).toLowerCase())
        || lower.contains(("from " + stem).toLowerCase())
        || lower.contains(("include " + targetBase).toLowerCase())
        || lower.contains(("include " + stem).toLowerCase());
  }

  private static String relativeModule(String dependentPath, String targetPath) {
    java.nio.file.Path parent = java.nio.file.Path.of(dependentPath).getParent();
    java.nio.file.Path target = java.nio.file.Path.of(targetPath);
    String value = (parent == null ? java.nio.file.Path.of("") : parent)
        .relativize(target).toString().replace('\\', '/');
    value = value.replaceAll("\\.(tsx?|jsx?)$", "");
    return value.startsWith(".") ? value : "./" + value;
  }

  private Project owned(UUID ownerId, UUID projectId) {
    return projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
  }

  private Review ownedReview(UUID ownerId, UUID reviewId) {
    Review review = reviews.findById(reviewId)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
    Project project = review.getProject();
    if (project.getDeletedAt() != null || !project.getOwner().getId().equals(ownerId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found");
    }
    return review;
  }

  public ReviewResponse toResponse(Review review) {
    List<ReviewResponse.AnalysisCheckResponse> checkResponses =
        checks.findByReviewIdOrderByCreatedAtAsc(review.getId()).stream()
            .map(this::toCheckResponse)
            .toList();
    List<ReviewResponse.AnalysisFileResponse> fileResponses =
        fileResults.findByReviewIdOrderByPathAsc(review.getId()).stream()
            .map(this::toFileResponse)
            .toList();
    GenerationResponse.RatingView rating =
        GenerationRatingCalculator.rate(findings.findByReviewId(review.getId()));
    int filesTotal = (int) projectFiles.countByProjectId(review.getProject().getId());
    int filesAnalyzed = (int) fileResponses.stream()
        .filter(file -> "ANALYZED".equals(file.status()))
        .count();
    List<ReviewResponse.ActivityResponse> activity = new java.util.ArrayList<>();
    activity.add(new ReviewResponse.ActivityResponse("Analysis queued", review.getCreatedAt()));
    if (review.getStartedAt() != null) {
      activity.add(new ReviewResponse.ActivityResponse("Analysis started", review.getStartedAt()));
    }
    if (filesAnalyzed > 0) {
      activity.add(new ReviewResponse.ActivityResponse(
          "Analyzed " + filesAnalyzed + " of " + filesTotal + " files", review.getStartedAt()));
    }
    if (review.getFinishedAt() != null) {
      activity.add(new ReviewResponse.ActivityResponse(
          review.getStatus() == ReviewStatus.FAILED
              ? "Analysis failed"
              : review.getStatus() == ReviewStatus.NOT_ANALYZED
                  ? "No checks could run"
                  : "Analysis completed",
          review.getFinishedAt()));
    }
    return new ReviewResponse(
        review.getId(),
        review.getProject().getId(),
        review.getStatus(),
        review.getStartedAt(),
        review.getFinishedAt(),
        review.getDurationMs(),
        review.getFindingCount(),
        review.getFixRound(),
        review.getError(),
        filesAnalyzed,
        filesTotal,
        checkResponses,
        fileResponses,
        rating,
        activity,
        review.getCreatedAt());
  }

  private ReviewResponse.AnalysisFileResponse toFileResponse(AnalysisFileResult file) {
    return new ReviewResponse.AnalysisFileResponse(
        file.getPath(),
        file.getStatus().name(),
        file.getReason(),
        file.getSizeBytes(),
        file.getFindingCount());
  }

  private ReviewResponse.AnalysisCheckResponse toCheckResponse(AnalysisCheck check) {
    return new ReviewResponse.AnalysisCheckResponse(
        check.getAnalyzer(),
        check.getStatus().name(),
        check.getReason(),
        check.getDurationMs(),
        check.getFilesAnalyzed(),
        check.getFindingCount());
  }

  static FindingResponse toFindingResponse(Finding finding) {
    return toFindingResponse(finding, new ObjectMapper());
  }

  FindingResponse toFindingResponseInstance(Finding finding) {
    return toFindingResponse(finding, objects);
  }

  private static FindingResponse toFindingResponse(Finding finding, ObjectMapper objects) {
    String analyzer = finding.getAnalyzer();
    String rule = finding.getRule();
    try {
      if (finding.getEvidence() != null) {
        JsonNode evidence = objects.readTree(finding.getEvidence());
        if (analyzer == null) {
          analyzer = textOrNull(evidence, "analyzer");
        }
        if (rule == null) {
          rule = textOrNull(evidence, "rule");
        }
      }
    } catch (Exception ignored) {
      // Evidence is best-effort display metadata; the row stays valid.
    }
    return new FindingResponse(
        finding.getId(),
        finding.getReview().getId(),
        finding.getCategory(),
        finding.getSeverity(),
        finding.getSource(),
        finding.getStatus(),
        analyzer,
        rule,
        finding.getTitle(),
        finding.getDescription(),
        finding.getFilePath(),
        finding.getLineStart(),
        finding.getLineEnd(),
        finding.getEvidence(),
        finding.getEvidenceSnippet(),
        finding.getExplanation(),
        finding.getSuggestedFix(),
        finding.getConfidence(),
        finding.isToolConfirmed(),
        finding.getDedupKey(),
        finding.getCreatedAt());
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode child = node.get(field);
    return child == null || child.isNull() ? null : child.asString(null);
  }
}
