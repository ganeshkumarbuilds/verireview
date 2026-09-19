package com.verireview.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.audit.AuditLogRepository;
import com.verireview.persistence.AbstractPersistenceTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 6 end-to-end (real Docker sandbox, pinned image): trigger → poll →
 * deterministic findings with rule/file/line refs, rerun reproducibility,
 * ownership, busy guard, and failure cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisJobTest extends AbstractPersistenceTest {

  private static final Duration DEADLINE = Duration.ofMinutes(6);

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Autowired
  private AuditLogRepository auditLogs;

  @Test
  void fullAnalysisPersistsNormalizedFindings() throws Exception {
    String token = access(register());
    String projectId = uploadProject(token, sampleZip());

    String reviewId = trigger(token, projectId);
    JsonNode review = awaitCompleted(token, reviewId);
    assertThat(review.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(review.get("findingCount").asInt()).isGreaterThan(0);

    JsonNode findings = findings(token, reviewId, "");
    assertThat(findings.get("totalElements").asInt()).isGreaterThan(0);
    boolean hasLineLength = false;
    boolean hasDeterministicSource = true;
    for (JsonNode finding : findings.get("content")) {
      assertThat(finding.get("rule").asString()).isNotBlank();
      assertThat(finding.get("analyzer").asString()).isNotBlank();
      if ("LineLength".equals(finding.get("rule").asString())) {
        hasLineLength = true;
        assertThat(finding.get("filePath").asString()).isEqualTo("Sample.java");
        assertThat(finding.get("lineStart").asInt()).isGreaterThan(0);
      }
      hasDeterministicSource &= "DETERMINISTIC".equals(finding.get("source").asString());
      assertThat(finding.get("dedupKey").asString()).hasSize(64);
    }
    assertThat(hasLineLength).isTrue();
    assertThat(hasDeterministicSource).isTrue();

    // Severity filter narrows the set through the same endpoint.
    JsonNode high = findings(token, reviewId, "&severity=HIGH");
    assertThat(high.get("totalElements").asInt())
        .isLessThanOrEqualTo(findings.get("totalElements").asInt());

    assertThat(auditLogs.findAll().stream()
        .anyMatch(row -> "ANALYSIS_COMPLETED".equals(row.getAction()))).isTrue();
  }

  @Test
  void rerunIsReproducible() throws Exception {
    String token = access(register());
    String projectId = uploadProject(token, sampleZip());

    JsonNode first = awaitCompleted(token, trigger(token, projectId));
    JsonNode second = awaitCompleted(token, trigger(token, projectId));
    assertThat(first.get("id").asString()).isNotEqualTo(second.get("id").asString());
    assertThat(keySet(token, second.get("id").asString()))
        .isEqualTo(keySet(token, first.get("id").asString()));
  }

  @Test
  void concurrentTriggerIsRejected() throws Exception {
    String token = access(register());
    String projectId = uploadProject(token, sampleZip());
    trigger(token, projectId);
    mockMvc.perform(post("/api/v1/projects/" + projectId + "/analysis")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
  }

  @Test
  void ownershipIsEnforced() throws Exception {
    String owner = access(register());
    String stranger = access(register());
    String projectId = uploadProject(owner, sampleZip());

    mockMvc.perform(post("/api/v1/projects/" + projectId + "/analysis")
            .header("Authorization", "Bearer " + stranger))
        .andExpect(status().isNotFound());

    String reviewId = trigger(owner, projectId);
    JsonNode review = awaitCompleted(owner, reviewId);
    assertThat(review.get("status").asString()).isEqualTo("COMPLETED");

    mockMvc.perform(get("/api/v1/reviews/" + reviewId)
            .header("Authorization", "Bearer " + stranger))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/reviews/" + reviewId + "/findings")
            .header("Authorization", "Bearer " + stranger))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/projects/" + projectId + "/reviews")
            .header("Authorization", "Bearer " + stranger))
        .andExpect(status().isNotFound());
  }

  @Test
  void emptyShellCompletesWithZeroFindings() throws Exception {
    String token = access(register());
    String name = "empty-" + UUID.randomUUID();
    MvcResult created = mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    String projectId =
        objects.readTree(created.getResponse().getContentAsString()).get("id").asText();

    JsonNode review = awaitCompleted(token, trigger(token, projectId));
    assertThat(review.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(review.get("findingCount").asInt()).isEqualTo(0);
  }

  @Test
  void unknownProjectIs404() throws Exception {
    String token = access(register());
    mockMvc.perform(post("/api/v1/projects/" + UUID.randomUUID() + "/analysis")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  private String trigger(String token, String projectId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/analysis")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private JsonNode awaitCompleted(String token, String reviewId) throws Exception {
    Instant deadline = Instant.now().plus(DEADLINE);
    while (true) {
      MvcResult result = mockMvc.perform(get("/api/v1/reviews/" + reviewId)
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode review = objects.readTree(result.getResponse().getContentAsString());
      String status = review.get("status").asString();
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        return review;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException("Analysis did not finish in time: " + reviewId);
      }
      Thread.sleep(3000);
    }
  }

  private JsonNode findings(String token, String reviewId, String extra) throws Exception {
    MvcResult result = mockMvc.perform(
            get("/api/v1/reviews/" + reviewId + "/findings?size=200" + extra)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString());
  }

  private Set<String> keySet(String token, String reviewId) throws Exception {
    JsonNode page = findings(token, reviewId, "");
    Set<String> keys = new HashSet<>();
    for (JsonNode finding : page.get("content")) {
      keys.add(finding.get("dedupKey").asString());
    }
    return keys;
  }

  private String uploadProject(String token, byte[] zip) throws Exception {
    MvcResult result = mockMvc.perform(multipart("/api/v1/projects/import/zip")
            .file(new MockMultipartFile("file", "sample.zip", "application/zip", zip))
            .param("name", "analysis-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private static byte[] sampleZip() throws Exception {
    String sample = """
        public class Sample {
          public static void main(String[] args) {
            System.out.println("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
          }
        }
        """;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("Sample.java"));
      zip.write(sample.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private String register() throws Exception {
    String email = "analysis-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"An\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
