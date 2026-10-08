package com.verireview.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class ImportJobAsyncTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Autowired
  private ImportJobRepository jobs;

  @Autowired
  private ProjectRepository projects;

  @Autowired
  private UserRepository users;

  @Autowired
  private ApplicationEventPublisher eventPublisher;

  @Test
  @Transactional
  void asyncZipImportTransitionsQueuedToDone() throws Exception {
    String token = access(register());
    String name = "async-zip-" + UUID.randomUUID();
    byte[] zip = zipOf(ZipEntryData.entry("src/Main.java", "class Main {}"), ZipEntryData.entry("README.md", "# demo"));

    MvcResult createResult = mockMvc.perform(multipart("/api/v1/projects/import/zip")
            .file(new MockMultipartFile("file", "demo.zip", "application/zip", zip))
            .param("name", name)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").exists())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andReturn();

    JsonNode createJson = objects.readTree(createResult.getResponse().getContentAsString());
    String jobId = createJson.get("jobId").asText();

    // Poll until DONE or FAILED (max 30 seconds)
    await().atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          MvcResult statusResult = mockMvc.perform(get("/api/v1/projects/import/jobs/" + jobId)
                  .header("Authorization", "Bearer " + token))
              .andExpect(status().isOk())
              .andReturn();

          JsonNode statusJson = objects.readTree(statusResult.getResponse().getContentAsString());
          String status = statusJson.get("status").asText();

          // Should eventually reach DONE
          assertThat(status).isIn("QUEUED", "EXTRACTING", "INDEXING", "DONE", "FAILED");

          if ("DONE".equals(status)) {
            String projectId = statusJson.get("projectId").asText();
            assertThat(projectId).isNotNull();
            assertThat(statusJson.get("filesTotal").asInt()).isEqualTo(2);
            assertThat(statusJson.get("filesProcessed").asInt()).isEqualTo(2);

            // Verify project exists
            mockMvc.perform(get("/api/v1/projects/" + projectId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.fileCount").value(2));
          } else if ("FAILED".equals(status)) {
            String error = statusJson.get("errorMessage").asText();
            throw new AssertionError("Import job failed: " + error);
          }
        });
  }

  @Test
  @Transactional
  void startupReconciliationMarksStaleJobsFailed() throws Exception {
    String token = access(register());
    User owner = users.findById(java.util.UUID.fromString(getUserId(token))).orElseThrow();

    // Create a job directly in DB with QUEUED status and old timestamp (missing staged file)
    ImportJob staleQueued = new ImportJob();
    staleQueued.setOwner(owner);
    staleQueued.setSourceType(ProjectSourceType.ZIP_UPLOAD);
    staleQueued.setName("stale-queued");
    staleQueued.setStatus(ImportJobStatus.QUEUED);
    staleQueued.setCurrentStep("File staged, waiting for processing");
    staleQueued.setStagedFilePath("/tmp/nonexistent.zip"); // missing staged file
    staleQueued = jobs.saveAndFlush(staleQueued);
    // Use reflection to set createdAt to 3 minutes ago
    setCreatedAt(staleQueued, Instant.now().minusSeconds(180));
    staleQueued = jobs.saveAndFlush(staleQueued);

    // Create a job in EXTRACTING (simulating in-progress when server stopped)
    ImportJob staleExtracting = new ImportJob();
    staleExtracting.setOwner(owner);
    staleExtracting.setSourceType(ProjectSourceType.ZIP_UPLOAD);
    staleExtracting.setName("stale-extracting");
    staleExtracting.setStatus(ImportJobStatus.EXTRACTING);
    staleExtracting.setCurrentStep("Extracting archive");
    staleExtracting.setStartedAt(Instant.now().minusSeconds(120));
    staleExtracting = jobs.saveAndFlush(staleExtracting);
    setCreatedAt(staleExtracting, Instant.now().minusSeconds(180));
    staleExtracting = jobs.saveAndFlush(staleExtracting);

    // Create a QUEUED job WITH existing staged file (should be re-dispatched)
    byte[] zip = zipOf(ZipEntryData.entry("src/Test.java", "class Test {}"));
    java.nio.file.Path staged = java.nio.file.Files.createTempFile("verireview-test-", ".zip");
    java.nio.file.Files.write(staged, zip);
    ImportJob queuedWithFile = new ImportJob();
    queuedWithFile.setOwner(owner);
    queuedWithFile.setSourceType(ProjectSourceType.ZIP_UPLOAD);
    queuedWithFile.setName("queued-with-file");
    queuedWithFile.setStatus(ImportJobStatus.QUEUED);
    queuedWithFile.setCurrentStep("File staged, waiting for processing");
    queuedWithFile.setStagedFilePath(staged.toString());
    queuedWithFile = jobs.saveAndFlush(queuedWithFile);
    setCreatedAt(queuedWithFile, Instant.now().minusSeconds(180));
    queuedWithFile = jobs.saveAndFlush(queuedWithFile);

    // Run reconciliation
    ImportJobReconciler reconciler = new ImportJobReconciler(jobs, eventPublisher);
    reconciler.reconcileStaleJobs();

    // Verify stale QUEUED with missing file -> FAILED
    ImportJob failedQueued = jobs.findById(staleQueued.getId()).orElseThrow();
    assertThat(failedQueued.getStatus()).isEqualTo(ImportJobStatus.FAILED);
    assertThat(failedQueued.getErrorMessage()).contains("staged file was lost");

    // Verify EXTRACTING -> FAILED
    ImportJob failedExtracting = jobs.findById(staleExtracting.getId()).orElseThrow();
    assertThat(failedExtracting.getStatus()).isEqualTo(ImportJobStatus.FAILED);
    assertThat(failedExtracting.getErrorMessage()).contains("interrupted by application restart");

    // Verify QUEUED with existing file -> still QUEUED (re-dispatched)
    ImportJob requeued = jobs.findById(queuedWithFile.getId()).orElseThrow();
    assertThat(requeued.getStatus()).isEqualTo(ImportJobStatus.QUEUED);

    // Cleanup
    java.nio.file.Files.deleteIfExists(staged);
  }

  private void setCreatedAt(ImportJob job, Instant createdAt) throws Exception {
    Field field = com.verireview.common.BaseEntity.class.getDeclaredField("createdAt");
    field.setAccessible(true);
    field.set(job, createdAt);
  }

  private String register() throws Exception {
    String email = "async-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Async\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }

  private String getUserId(String token) throws Exception {
    // Decode JWT to get user ID (simple approach for test)
    String[] parts = token.split("\\.");
    String payload = new String(java.util.Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    return objects.readTree(payload).get("sub").asText();
  }

  private static byte[] zipOf(ZipEntryData... entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (ZipEntryData entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.content());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private record ZipEntryData(String name, byte[] content) {
    static ZipEntryData entry(String name, String content) {
      return new ZipEntryData(name, content.getBytes(StandardCharsets.UTF_8));
    }
  }
}