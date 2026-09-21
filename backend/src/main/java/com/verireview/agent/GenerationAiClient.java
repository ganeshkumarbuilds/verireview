package com.verireview.agent;

import com.verireview.agent.dto.AiGenerationFilesRequest;
import com.verireview.agent.dto.AiGenerationFilesResult;
import com.verireview.agent.dto.AiGenerationPlanRequest;
import com.verireview.agent.dto.AiGenerationPlanResult;
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
 * Server-to-server client for {@code POST /internal/generate/plan} and
 * {@code POST /internal/generate/files}. Same pattern as the review/coding
 * clients: bounded timeouts, secret in the server-only header, request bodies
 * are never logged (they carry the per-run AI key).
 */
@Component
public class GenerationAiClient {

  private static final String SECRET_HEADER = "X-Internal-Secret";

  private final AiServiceProperties props;
  private final ObjectMapper objects;
  private final HttpClient http;

  @Autowired
  public GenerationAiClient(AiServiceProperties props, ObjectMapper objects) {
    this(props, objects, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  GenerationAiClient(AiServiceProperties props, ObjectMapper objects, HttpClient http) {
    this.props = props;
    this.objects = objects;
    this.http = http;
  }

  public AiGenerationPlanResult plan(AiGenerationPlanRequest request) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("generation_id", request.generationId());
    root.put("requirement", request.requirement());
    root.put("backend", request.backend());
    root.put("frontend", request.frontend());
    root.put("database", request.database());
    root.put("db_host", request.dbHost());
    root.put("db_port", request.dbPort());
    root.put("db_name", request.dbName());
    root.put("db_username", request.dbUsername());
    root.put("db_ssl_mode", request.dbSslMode());
    root.put("ai_provider", request.aiProvider());
    root.put("api_key", request.apiKey());
    root.put("base_url", request.baseUrl());
    root.put("model", request.model());
    String body = post("/internal/generate/plan", root);
    return parsePlan(body, request.generationId());
  }

  public AiGenerationFilesResult files(AiGenerationFilesRequest request) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("generation_id", request.generationId());
    root.put("requirement", request.requirement());
    root.put("backend", request.backend());
    root.put("frontend", request.frontend());
    root.put("database", request.database());
    root.put("db_host", request.dbHost());
    root.put("db_port", request.dbPort());
    root.put("db_name", request.dbName());
    root.put("db_username", request.dbUsername());
    root.put("db_ssl_mode", request.dbSslMode());
    root.put("ai_provider", request.aiProvider());
    root.put("api_key", request.apiKey());
    root.put("base_url", request.baseUrl());
    root.put("model", request.model());
    List<Object> plan = new ArrayList<>();
    for (AiGenerationFilesRequest.PlannedFileRef ref : request.plan()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("path", ref.path());
      item.put("purpose", ref.purpose());
      plan.add(item);
    }
    root.put("plan", plan);
    String body = post("/internal/generate/files", root);
    return parseFiles(body, request.generationId());
  }

  private String post(String path, Map<String, Object> root) {
    final String body;
    try {
      body = objects.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encode AI generation request", e);
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(props.url() + path))
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
          "AI generation call timed out after " + props.timeoutSeconds() + "s", e);
    } catch (IOException e) {
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "AI generation service unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "AI generation call interrupted", e);
    }
    if (response.statusCode() != 200) {
      throw new AiServiceException(AiServiceException.Kind.BAD_STATUS,
          "AI generation service answered HTTP " + response.statusCode()
              + truncateBody(response.body()));
    }
    return response.body();
  }

  /**
   * Appends the service's own error detail (e.g. FastAPI 422 field errors)
   * so a rejection names the offending field instead of a bare status code.
   * Responses carry results, never the per-run key — still, cap the length.
   */
  private static String truncateBody(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String detail = body.length() <= 2000 ? body : body.substring(0, 2000) + "…[truncated]";
    return ": " + detail;
  }

  private AiGenerationPlanResult parsePlan(String body, String expectedId) {
    JsonNode root = readRoot(body);
    expectId(root, expectedId);
    List<AiGenerationPlanResult.PlannedFile> files = new ArrayList<>();
    JsonNode items = root.get("files");
    if (items != null && items.isArray()) {
      for (JsonNode item : items) {
        String path = text(item.get("path"));
        if (path == null || path.isBlank()) {
          throw new AiServiceException(AiServiceException.Kind.MALFORMED,
              "AI generation plan contains a file without a path");
        }
        files.add(new AiGenerationPlanResult.PlannedFile(path, textOrEmpty(item.get("purpose"))));
      }
    }
    if (files.isEmpty()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation plan contains no files");
    }
    return new AiGenerationPlanResult(expectedId, List.copyOf(files),
        textOrEmpty(root.get("notes")), parseSections(root));
  }

  private AiGenerationPlanResult.PlanSections parseSections(JsonNode root) {
    String architecture = cappedText(root.get("architecture"), 10000, "architecture");
    String apis = cappedText(root.get("apis"), 10000, "apis");
    return new AiGenerationPlanResult.PlanSections(
        architecture,
        stringList(root.get("dependencies"), "dependencies", 200, 500),
        stringList(root.get("directories"), "directories", 200, 1000),
        apis,
        stringList(root.get("steps"), "steps", 100, 2000));
  }

  private String cappedText(JsonNode node, int maxChars, String field) {
    if (node == null || node.isNull()) {
      return "";
    }
    if (!node.isTextual()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation plan section '" + field + "' must be a string");
    }
    String value = node.asText();
    if (value.length() > maxChars) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation plan section '" + field + "' exceeds size limit");
    }
    return value;
  }

  private List<String> stringList(JsonNode node, String field, int maxItems, int maxChars) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation plan section '" + field + "' must be a list");
    }
    List<String> items = new ArrayList<>();
    for (JsonNode item : node) {
      if (!item.isTextual() || item.asText().isBlank()) {
        throw new AiServiceException(AiServiceException.Kind.MALFORMED,
            "AI generation plan section '" + field + "' entries must be non-empty strings");
      }
      if (item.asText().length() > maxChars) {
        throw new AiServiceException(AiServiceException.Kind.MALFORMED,
            "AI generation plan section '" + field + "' entry exceeds size limit");
      }
      items.add(item.asText().trim());
    }
    if (items.size() > maxItems) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation plan section '" + field + "' exceeds item limit");
    }
    return List.copyOf(items);
  }

  private AiGenerationFilesResult parseFiles(String body, String expectedId) {
    JsonNode root = readRoot(body);
    expectId(root, expectedId);
    List<AiGenerationFilesResult.GeneratedFile> files = new ArrayList<>();
    JsonNode items = root.get("files");
    if (items != null && items.isArray()) {
      for (JsonNode item : items) {
        String path = text(item.get("path"));
        String content = text(item.get("content"));
        if (path == null || path.isBlank() || content == null) {
          throw new AiServiceException(AiServiceException.Kind.MALFORMED,
              "AI generation returned a file without path or content");
        }
        files.add(new AiGenerationFilesResult.GeneratedFile(
            path, content, textOrEmpty(item.get("language"))));
      }
    }
    if (files.isEmpty()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation returned no files");
    }
    return new AiGenerationFilesResult(expectedId, List.copyOf(files), textOrEmpty(root.get("notes")));
  }

  private JsonNode readRoot(String body) {
    final JsonNode root;
    try {
      root = objects.readTree(body);
    } catch (Exception e) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation service returned unreadable JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation service returned non-object");
    }
    return root;
  }

  private void expectId(JsonNode root, String expectedId) {
    String id = text(root.get("generation_id"));
    if (id == null || !id.equals(expectedId)) {
      throw new AiServiceException(AiServiceException.Kind.MALFORMED,
          "AI generation answered for a different generation");
    }
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.asText();
  }

  private static String textOrEmpty(JsonNode node) {
    String value = text(node);
    return value == null ? "" : value;
  }
}
