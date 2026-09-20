package com.verireview.agent;

import com.verireview.generation.GenerationService;
import com.verireview.generation.GenerationExecutionEvidence;
import com.verireview.verification.VerificationVerdict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the Verified Agent (AI service /internal/verify).
 * READ-ONLY: evaluates build/test evidence and returns a verdict.
 * Never modifies files or project state.
 */
@Component
public class VerifiedAgentClient {

  private final RestClient restClient;
  private final ObjectMapper objects;
  private final String model;

  public VerifiedAgentClient(
      @Value("${app.ai-service.base-url:http://localhost:8001}") String baseUrl,
      @Value("${app.ai-service.secret:}") String secret,
      @Value("${app.generation.ai-model:openrouter/auto}") String model,
      ObjectMapper objects) {
    this.objects = objects;
    this.model = model;
    RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl + "/internal");
    if (!secret.isBlank()) {
      builder.defaultHeader("X-Internal-Secret", secret);
    }
    this.restClient = builder.build();
  }

  public VerifiedAgentResult evaluate(
      UUID generationId, int iteration,
      GenerationExecutionEvidence evidence,
      GenerationService.GenerationSnapshot snapshot) {

    Map<String, Object> request = new LinkedHashMap<>();
    Map<String, Object> evidenceMap = new LinkedHashMap<>();
    evidenceMap.put("generation_id", generationId.toString());
    evidenceMap.put("iteration", iteration);
    evidenceMap.put("command", evidence.getCommand());
    evidenceMap.put("exit_code", evidence.getExitCode());
    evidenceMap.put("duration_ms", evidence.getDurationMs());
    evidenceMap.put("stdout", evidence.getStdout());
    evidenceMap.put("stderr", evidence.getStderr());
    evidenceMap.put("build_status", evidence.getBuildStatus().name());
    evidenceMap.put("test_status", evidence.getTestStatus().name());
    evidenceMap.put("failure_reason", evidence.getFailureReason());
    request.put("evidence", evidenceMap);

    Map<String, Object> contextMap = new LinkedHashMap<>();
    contextMap.put("generation_id", generationId.toString());
    contextMap.put("requirement", snapshot.requirement());
    contextMap.put("backend", snapshot.backend().name());
    contextMap.put("frontend", snapshot.frontend().name());
    contextMap.put("database", snapshot.database().name());
    request.put("context", contextMap);

    request.put("model", model);
    request.put("prompt_version", "verified/v1");

    try {
      String responseBody = restClient.post()
          .uri("/verify")
          .contentType(MediaType.APPLICATION_JSON)
          .body(request)
          .retrieve()
          .body(String.class);

      JsonNode root = objects.readTree(responseBody);
      return new VerifiedAgentResult(
          root.get("verification_run_id").asText(),
          root.get("verdict").asText(),
          root.get("reason").asText(),
          root.get("tests_total").asInt(),
          root.get("tests_passed").asInt(),
          root.get("tests_failed").asInt(),
          root.get("tests_skipped").asInt(),
          root.path("log_ref").asText(null));

    } catch (Exception e) {
      throw new AiServiceException(AiServiceException.Kind.UNAVAILABLE,
          "Verified Agent unavailable: " + e.getMessage(), e);
    }
  }

  public record VerifiedAgentResult(
      String verificationRunId,
      String verdict,
      String reason,
      int testsTotal,
      int testsPassed,
      int testsFailed,
      int testsSkipped,
      String logRef) {

    public VerificationVerdict verdictEnum() {
      return VerificationVerdict.valueOf(verdict);
    }
  }
}