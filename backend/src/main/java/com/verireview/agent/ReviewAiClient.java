package com.verireview.agent;

import com.verireview.agent.dto.AiDeterministicFinding;
import com.verireview.agent.dto.AiFileSnapshot;
import com.verireview.agent.dto.AiProposedFinding;
import com.verireview.agent.dto.AiReviewRequest;
import com.verireview.agent.dto.AiReviewResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Server-to-server client for {@code POST /internal/review} (ARCHITECTURE
 * §4 contract). JDK {@code HttpClient} only — no new dependencies.
 * Bounded by connect + request timeouts; every failure mode surfaces as
 * {@link AiServiceException} so callers degrade without blocking forever.
 */
@Component
public class ReviewAiClient {

  private static final Logger log = LoggerFactory.getLogger(ReviewAiClient.class);
  private static final String SECRET_HEADER = "X-Internal-Secret";

  private final AiServiceProperties props;
  private final ObjectMapper objects;
  private final HttpClient http;

  @Autowired
  public ReviewAiClient(AiServiceProperties props, ObjectMapper objects) {
    this(props, objects,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  ReviewAiClient(AiServiceProperties props, ObjectMapper objects, HttpClient http) {
    this.props = props;
    this.objects = objects;
    this.http = http;
  }

  /** Sends one review invocation; see class javadoc for failure mapping. */
  public AiReviewResult review(AiReviewRequest request) {
    String body = encode(request);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(props.url() + "/internal/review"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(props.timeoutSeconds()))
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (!props.secret().isBlank()) {
      builder.header(SECRET_HEADER, props.secret());
    }
    HttpResponse<String> response;
    try {
      response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new AiServiceException(AiServiceException.Kind.TIMEOUT,
          "AI service call timed out after " + props.timeoutSeconds() + "s", e);
    } catch (IOException e) {
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "AI service unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "AI service call interrupted", e);
    }
    if (response.statusCode() != 200) {
      throw new AiServiceException(AiServiceException.Kind.BAD_STATUS,
          "AI service answered HTTP " + response.statusCode()
              + truncateBody(response.body()));
    }
    return parse(response.body(), request.reviewId());
  }

  private String encode(AiReviewRequest request) {
    try {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("review_id", request.reviewId());
      root.put("project_id", request.projectId());
      root.put("language", request.language());
      List<Object> files = new ArrayList<>();
      for (AiFileSnapshot file : request.files()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", file.path());
        item.put("language", file.language());
        item.put("content", file.content());
        item.put("truncated", file.truncated());
        files.add(item);
      }
      root.put("files", files);
      List<Object> hits = new ArrayList<>();
      for (AiDeterministicFinding hit : request.deterministicFindings()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", hit.tool());
        item.put("rule_id", hit.ruleId());
        item.put("file", hit.file());
        item.put("line", hit.line());
        item.put("message", hit.message());
        hits.add(item);
      }
      root.put("deterministic_findings", hits);
      root.put("idempotency_key", request.idempotencyKey());
      return objects.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode AI review request", e);
    }
  }

  /** Lenient parse: whole-body problems → MALFORMED; bad items are skipped. */
  AiReviewResult parse(String body, String expectedReviewId) {
    final JsonNode root;
    try {
      root = objects.readTree(body);
    } catch (Exception e) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI service returned unreadable JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI service returned a non-object body");
    }
    String reviewId = text(root.get("review_id"));
    if (reviewId == null || !reviewId.equals(expectedReviewId)) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI service answered for a different review");
    }
    JsonNode items = root.get("findings");
    if (items == null || !items.isArray()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI service returned no findings array");
    }
    List<AiProposedFinding> findings = new ArrayList<>();
    int invalid = 0;
    for (JsonNode item : items) {
      AiProposedFinding parsed = parseItem(item);
      if (parsed == null) {
        invalid++;
      } else {
        findings.add(parsed);
      }
    }
    return new AiReviewResult(
        reviewId,
        textOrEmpty(root.get("agent")),
        textOrEmpty(root.get("prompt_version")),
        findings,
        textOrEmpty(root.get("notes")),
        invalid);
  }

  private static AiProposedFinding parseItem(JsonNode item) {
    if (item == null || !item.isObject()) {
      return null;
    }
    String title = text(item.get("title"));
    if (title == null || title.isBlank()) {
      return null;
    }
    return new AiProposedFinding(
        text(item.get("category")),
        text(item.get("severity")),
        title,
        text(item.get("description")),
        text(item.get("file_path")),
        integer(item.get("line_start")),
        integer(item.get("line_end")),
        text(item.get("evidence")),
        text(item.get("source")),
        text(item.get("suggested_fix_hint")),
        decimal(item.get("confidence")));
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.asText();
  }

  /**
   * Appends the service's own error detail so a rejection names the cause
   * instead of a bare status code. Responses carry results, never secrets —
   * still, cap the length.
   */
  private static String truncateBody(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String detail = body.length() <= 2000 ? body : body.substring(0, 2000) + "…[truncated]";
    return ": " + detail;
  }

  private static String textOrEmpty(JsonNode node) {
    String value = text(node);
    return value == null ? "" : value;
  }

  private static Integer integer(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return Integer.parseInt(node.asText().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Double decimal(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return Double.parseDouble(node.asText().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
