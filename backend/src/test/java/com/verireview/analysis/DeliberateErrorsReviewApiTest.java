package com.verireview.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.persistence.AbstractPersistenceTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 2 reproduction through the production API boundary. This deliberately
 * disables Docker so the test exercises AnalysisRunner's in-process path,
 * which is the deployed fallback on hosts without Docker-in-Docker support.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.sandbox.docker-enabled=false",
    "app.ai-service.enabled=false"
})
class DeliberateErrorsReviewApiTest extends AbstractPersistenceTest {

  private static final Duration DEADLINE = Duration.ofSeconds(30);

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Test
  void importsFixtureTriggersRealReviewAndReportsCurrentFindings() throws Exception {
    String token = access(register());
    String projectId = importFixture(token);
    String reviewId = trigger(token, projectId);

    JsonNode review = awaitFinished(token, reviewId);
    assertThat(review.get("status").asString()).isEqualTo("COMPLETED");

    JsonNode page = findings(token, reviewId);
    List<String> findings = new ArrayList<>();
    for (JsonNode finding : page.get("content")) {
      findings.add(finding.get("filePath").asString()
          + ":" + finding.get("lineStart").asInt()
          + ":" + finding.get("category").asString());
    }

    assertThat(findings).containsExactlyInAnyOrder(
        "analysis-fixtures/deliberate-errors/src/App.js:3:SYNTAX",
        "analysis-fixtures/deliberate-errors/src/App.jsx:2:SYNTAX",
        "analysis-fixtures/deliberate-errors/package.json:1:SYNTAX",
        "analysis-fixtures/deliberate-errors/src/broken.py:3:SYNTAX",
        "analysis-fixtures/deliberate-errors/src/Broken.java:4:SYNTAX",
        "analysis-fixtures/deliberate-errors/src/config.js:1:SECURITY");
  }

  private String importFixture(String token) throws Exception {
    MvcResult result = mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile(
                "file", "deliberate-errors.zip", "application/zip", fixtureZip()))
            .param("name", "deliberate-errors-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  private String trigger(String token, String projectId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/analysis")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  private JsonNode awaitFinished(String token, String reviewId) throws Exception {
    Instant deadline = Instant.now().plus(DEADLINE);
    while (true) {
      MvcResult result = mockMvc.perform(get("/api/v1/reviews/" + reviewId)
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode review = objects.readTree(result.getResponse().getContentAsString());
      String status = review.get("status").asString();
      if ("COMPLETED".equals(status) || "FAILED".equals(status)
          || "NOT_ANALYZED".equals(status)) {
        return review;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException("Review did not finish in time: " + reviewId);
      }
      Thread.sleep(100);
    }
  }

  private JsonNode findings(String token, String reviewId) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/reviews/" + reviewId + "/findings?size=200")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString());
  }

  private String register() throws Exception {
    String email = "fixture-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Fixture\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws IOException {
    return objects.readTree(registrationBody).get("accessToken").asString();
  }

  private static byte[] fixtureZip() throws IOException {
    String[] paths = {
        "src/App.js", "src/App.jsx", "package.json", "src/broken.py",
        "src/Broken.java", "src/config.js"
    };
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String path : paths) {
        zip.putNextEntry(new ZipEntry("analysis-fixtures/deliberate-errors/" + path));
        try (InputStream stream = DeliberateErrorsReviewApiTest.class.getClassLoader()
            .getResourceAsStream("analysis-fixtures/deliberate-errors/" + path)) {
          if (stream == null) {
            throw new IOException("Missing fixture: " + path);
          }
          stream.transferTo(zip);
        }
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
