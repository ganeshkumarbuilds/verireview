package com.verireview.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verireview.agent.dto.AiDeterministicFinding;
import com.verireview.agent.dto.AiFileSnapshot;
import com.verireview.agent.dto.AiReviewRequest;
import com.verireview.agent.dto.AiReviewResult;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 7C client tests. The HTTP layer is mocked — no ai-service, no
 * OpenRouter, no network. Covers the wire contract and every failure kind.
 */
class ReviewAiClientTest {

  private static final ObjectMapper OBJECTS = new ObjectMapper();

  private ReviewAiClient client(HttpClient http, String secret) {
    AiServiceProperties props = new AiServiceProperties(
        "http://localhost:8001", secret, 5, true, "ai-service/review", 25, 16384);
    return new ReviewAiClient(props, OBJECTS, http);
  }

  @SuppressWarnings("unchecked")
  private static HttpResponse<String> okResponse(String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(body);
    return response;
  }

  private static AiReviewRequest request() {
    return new AiReviewRequest(
        "11111111-1111-1111-1111-111111111111",
        "22222222-2222-2222-2222-222222222222",
        "java",
        List.of(new AiFileSnapshot("Main.java", "java", "class Main {}", false)),
        List.of(new AiDeterministicFinding("checkstyle", "LineLength", "Main.java", 3, "long")),
        "review-1");
  }

  @Test
  void happyPathOverRealLoopbackHttp() throws Exception {
    String responseJson = "{"
        + "\"review_id\": \"11111111-1111-1111-1111-111111111111\","
        + "\"agent\": \"review\","
        + "\"prompt_version\": \"review/v1\","
        + "\"notes\": \"ok\","
        + "\"findings\": ["
        + " {\"category\": \"BUG\", \"severity\": \"HIGH\", \"title\": \"NPE\","
        + "  \"description\": \"d\", \"file_path\": \"Main.java\","
        + "  \"line_start\": 3, \"line_end\": 3, \"evidence\": \"e\","
        + "  \"source\": \"AI\", \"suggested_fix_hint\": \"h\", \"confidence\": 0.9},"
        + " {\"category\": \"NOPE\", \"title\": \"\"}"
        + "]}";
    List<String> bodies = new ArrayList<>();
    List<String> secrets = new ArrayList<>();
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/review", exchange -> {
      try {
        bodies.add(new String(
            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        secrets.add(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
        byte[] payload = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
      } finally {
        exchange.close();
      }
    });
    server.start();
    try {
      AiServiceProperties props = new AiServiceProperties(
          "http://127.0.0.1:" + server.getAddress().getPort(),
          "s3cret", 5, true, "ai-service/review", 25, 16384);
      AiReviewResult result = new ReviewAiClient(props, OBJECTS).review(request());

      assertThat(result.reviewId()).isEqualTo("11111111-1111-1111-1111-111111111111");
      assertThat(result.promptVersion()).isEqualTo("review/v1");
      assertThat(result.findings()).hasSize(1);
      assertThat(result.findings().get(0).title()).isEqualTo("NPE");
      assertThat(result.invalidItems()).isEqualTo(1);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.get(0)).contains("\"deterministic_findings\"");
      assertThat(bodies.get(0)).contains("\"rule_id\"");
      assertThat(secrets).containsExactly("s3cret");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void secretHeaderOmittedWhenUnconfigured() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> canned = okResponse(
        "{\"review_id\": \"11111111-1111-1111-1111-111111111111\","
            + " \"findings\": []}");
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(canned);

    ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
    AiReviewResult result = client(http, "").review(request());

    assertThat(result.findings()).isEmpty();
    verify(http).send(sent.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(sent.getValue().headers().firstValue("X-Internal-Secret")).isEmpty();
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void mismatchedReviewIdIsMalformed() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> canned =
        okResponse("{\"review_id\": \"other\", \"findings\": []}");
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(canned);
    assertThatThrownBy(() -> client(http, "").review(request()))
        .isInstanceOf(AiServiceException.class)
        .satisfies(e -> assertThat(((AiServiceException) e).kind())
            .isEqualTo(AiServiceException.Kind.MALFORMED));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void timeoutMapsToTimeoutKind() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new HttpTimeoutException("timed out"));
    assertThatThrownBy(() -> client(http, "").review(request()))
        .isInstanceOf(AiServiceException.class)
        .satisfies(e -> assertThat(((AiServiceException) e).kind())
            .isEqualTo(AiServiceException.Kind.TIMEOUT));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void connectionFailureMapsToUnavailableKind() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("refused"));
    assertThatThrownBy(() -> client(http, "").review(request()))
        .isInstanceOf(AiServiceException.class)
        .satisfies(e -> assertThat(((AiServiceException) e).kind())
            .isEqualTo(AiServiceException.Kind.UNAVAILABLE));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void errorStatusMapsToBadStatusKind() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(500);
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    assertThatThrownBy(() -> client(http, "").review(request()))
        .isInstanceOf(AiServiceException.class)
        .satisfies(e -> assertThat(((AiServiceException) e).kind())
            .isEqualTo(AiServiceException.Kind.BAD_STATUS));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void garbageBodyMapsToMalformedKind() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<String> canned = okResponse("not json {{{");
    when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(canned);
    assertThatThrownBy(() -> client(http, "").review(request()))
        .isInstanceOf(AiServiceException.class)
        .satisfies(e -> assertThat(((AiServiceException) e).kind())
            .isEqualTo(AiServiceException.Kind.MALFORMED));
  }
}
