package com.verireview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.verireview.agent.dto.AiGenerationPlanRequest;
import com.verireview.agent.dto.AiGenerationPlanResult;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Generation client wire contract. The HTTP layer is a real loopback server —
 * no ai-service, no OpenRouter, no network. Covers the happy path and the
 * requirement that non-200 answers surface the service's own error detail
 * (e.g. FastAPI 422 field errors) instead of a bare status code.
 */
class GenerationAiClientTest {

  private static final ObjectMapper OBJECTS = new ObjectMapper();

  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    bodies.clear();
  }

  private GenerationAiClient client(int port) {
    AiServiceProperties props = new AiServiceProperties(
        "http://127.0.0.1:" + port, "", 5, true, "ai-service/generation", 25, 16384);
    return new GenerationAiClient(props, OBJECTS,
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build());
  }

  private int serve(String path, int status, String responseJson) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(path, exchange -> {
      try {
        bodies.add(new String(
            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] payload = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
      } finally {
        exchange.close();
      }
    });
    server.start();
    return server.getAddress().getPort();
  }

  private static AiGenerationPlanRequest request() {
    return new AiGenerationPlanRequest(
        "33333333-3333-3333-3333-333333333333",
        "A minimal todo API with tests",
        "PYTHON_FASTAPI", "NONE", "NONE",
        null, null, null, null, null,
        "OPENROUTER", "sk-test-key", null, "test/model");
  }

  @Test
  void planParsesHappyPathAndSendsWireContract() throws Exception {
    int port = serve("/internal/generate/plan", 200,
        "{\"generation_id\":\"33333333-3333-3333-3333-333333333333\","
            + "\"files\":[{\"path\":\"app/main.py\",\"purpose\":\"entrypoint\"}],"
            + "\"architecture\":\"Single-module app\","
            + "\"dependencies\":[],\"directories\":[\"app\"],\"apis\":\"\","
            + "\"steps\":[],\"notes\":\"ok\"}");
    AiGenerationPlanResult result = client(port).plan(request());
    assertThat(result.files()).hasSize(1);
    assertThat(result.files().get(0).path()).isEqualTo("app/main.py");
    assertThat(result.sections().architecture()).isEqualTo("Single-module app");
    // Wire contract: snake_case keys, per-run key in the body only.
    assertThat(bodies).hasSize(1);
    assertThat(bodies.get(0)).contains("\"api_key\":\"sk-test-key\"");
    assertThat(bodies.get(0)).contains("\"model\":\"test/model\"");
  }

  @Test
  void non200SurfacesServiceErrorDetailInsteadOfBareStatus() throws Exception {
    String detail = "{\"detail\":[{\"loc\":[\"body\",\"api_key\"],"
        + "\"msg\":\"String should have at least 1 character\","
        + "\"type\":\"string_too_short\"}]}";
    int port = serve("/internal/generate/plan", 422, detail);
    assertThatThrownBy(() -> client(port).plan(request()))
        .isInstanceOf(AiServiceException.class)
        .hasMessageContaining("422")
        .hasMessageContaining("api_key")
        .hasMessageContaining("string_too_short");
  }
}
