package com.verireview.agent;

import com.verireview.agent.dto.AiCodingRequest;
import com.verireview.agent.dto.AiCodingResult;
import com.verireview.agent.dto.AiFileSnapshot;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Server-to-server client for {@code POST /internal/coding} (and alias
 * {@code /internal/code-fix}). Bounded timeouts, no secrets to frontend.
 */
@Component
public class CodingAiClient {

  private static final String SECRET_HEADER = "X-Internal-Secret";

  private final AiServiceProperties props;
  private final ObjectMapper objects;
  private final HttpClient http;

  @Autowired
  public CodingAiClient(AiServiceProperties props, ObjectMapper objects) {
    this(props, objects, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  CodingAiClient(AiServiceProperties props, ObjectMapper objects, HttpClient http) {
    this.props = props;
    this.objects = objects;
    this.http = http;
  }

  public AiCodingResult coding(AiCodingRequest request) {
    String body = encode(request);
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(props.url() + "/internal/coding"))
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
          "AI coding call timed out after " + props.timeoutSeconds() + "s", e);
    } catch (IOException e) {
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "AI coding service unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "AI coding call interrupted", e);
    }
    if (response.statusCode() != 200) {
      throw new AiServiceException(AiServiceException.Kind.BAD_STATUS,
          "AI coding service answered HTTP " + response.statusCode());
    }
    return parse(response.body(), request.fixRequestId());
  }

  private String encode(AiCodingRequest request) {
    try {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("fix_request_id", request.fixRequestId());
      Map<String, Object> finding = new LinkedHashMap<>();
      finding.put("id", request.finding().id());
      finding.put("title", request.finding().title());
      finding.put("description", request.finding().description());
      finding.put("file_path", request.finding().filePath());
      finding.put("line_start", request.finding().lineStart());
      finding.put("line_end", request.finding().lineEnd());
      finding.put("category", request.finding().category());
      finding.put("severity", request.finding().severity());
      finding.put("evidence", request.finding().evidence());
      root.put("finding", finding);
      root.put("scope_note", request.scopeNote());
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
      root.put("idempotency_key", request.idempotencyKey());
      return objects.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode AI coding request", e);
    }
  }

  AiCodingResult parse(String body, String expectedFixRequestId) {
    final JsonNode root;
    try {
      root = objects.readTree(body);
    } catch (Exception e) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI coding service returned unreadable JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI coding service returned non-object");
    }
    String fixId = text(root.get("fix_request_id"));
    if (fixId == null || !fixId.equals(expectedFixRequestId)) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI coding answered for different fix request");
    }
    String diff = text(root.get("diff"));
    if (diff == null || diff.isBlank()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI coding returned empty diff");
    }
    // Validate diff shape before accepting
    String trimmed = diff.trim();
    if (!trimmed.contains("diff --git") || !trimmed.contains("---") || !trimmed.contains("+++") || !trimmed.contains("@@")) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI coding returned invalid diff format");
    }
    if (trimmed.toLowerCase().contains("verified") || trimmed.toLowerCase().contains("tests passed") || trimmed.toLowerCase().contains("build passed")) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI coding diff must not claim verification");
    }
    int filesChanged = integer(root.get("files_changed"), 0);
    int additions = integer(root.get("additions"), 0);
    int deletions = integer(root.get("deletions"), 0);
    // If counts not provided, derive from diff
    if (filesChanged == 0 && additions == 0 && deletions == 0) {
      int[] stats = countDiffStats(trimmed);
      filesChanged = stats[0];
      additions = stats[1];
      deletions = stats[2];
    }
    return new AiCodingResult(
        fixId,
        textOrEmpty(root.get("agent")),
        textOrEmpty(root.get("prompt_version")),
        trimmed,
        Math.max(0, filesChanged),
        Math.max(0, additions),
        Math.max(0, deletions),
        textOrEmpty(root.get("explanation")),
        textOrEmpty(root.get("notes")));
  }

  private static int[] countDiffStats(String diff) {
    java.util.Set<String> files = new java.util.HashSet<>();
    int adds = 0, dels = 0;
    for (String line : diff.split("\n")) {
      if (line.startsWith("diff --git")) {
        String[] parts = line.split(" ");
        if (parts.length >= 4) {
          String path = parts[3];
          if (path.startsWith("b/")) path = path.substring(2);
          files.add(path);
        }
      } else if (line.startsWith("+++ ") || line.startsWith("--- ")) {
        continue;
      } else if (line.startsWith("+") && !line.startsWith("+++")) {
        adds++;
      } else if (line.startsWith("-") && !line.startsWith("---")) {
        dels++;
      }
    }
    return new int[]{files.size(), adds, dels};
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) return null;
    return node.asText();
  }

  private static String textOrEmpty(JsonNode node) {
    String v = text(node);
    return v == null ? "" : v;
  }

  private static int integer(JsonNode node, int def) {
    if (node == null || node.isNull()) return def;
    try {
      return Integer.parseInt(node.asText().trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
