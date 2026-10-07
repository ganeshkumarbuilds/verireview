package com.verireview.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.audit.AuditLogRepository;
import com.verireview.persistence.AbstractPersistenceTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
 * Phase 5 ingestion flow + security (API_DESIGN §2/§4, SECURITY_DESIGN §3):
 * shells, ownership isolation, happy-path ZIP import, and attack fixtures
 * (traversal, absolute paths, file/dir conflicts, magic bytes, file-count,
 * single-file, and decompression-bomb caps).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectIngestionTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Autowired
  private AuditLogRepository auditLogs;

  @Test
  void createShellListUpdateDelete() throws Exception {
    String token = access(register());
    String name = "shell-" + UUID.randomUUID();

    MvcResult created = mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\",\"description\":\"demo\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value(name))
        .andExpect(jsonPath("$.fileCount").value(0))
        .andExpect(jsonPath("$.storageRef").doesNotExist())
        .andReturn();
    String id = objects.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isConflict());

    mockMvc.perform(get("/api/v1/projects")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").value(1));

    mockMvc.perform(patch("/api/v1/projects/" + id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("renamed"));

    mockMvc.perform(delete("/api/v1/projects/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/v1/projects/" + id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());

    assertThat(auditLogs.findAll().stream()
        .anyMatch(row -> "PROJECT_CREATED".equals(row.getAction()))).isTrue();
  }

  @Test
  void usersCannotSeeEachOthersProjects() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    String name = "owned-" + UUID.randomUUID();

    MvcResult created = mockMvc.perform(post("/api/v1/projects")
            .header("Authorization", "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    String id = objects.readTree(created.getResponse().getContentAsString()).get("id").asText();

    // Foreign project behaves as missing (enumeration resistance).
    mockMvc.perform(get("/api/v1/projects/" + id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/projects")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc.perform(delete("/api/v1/projects/" + id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void zipImportStoresMetadataAndServesFiles() throws Exception {
    String token = access(register());
    String name = "zip-" + UUID.randomUUID();
    byte[] zip = zipOf(
        entry("src/Main.java", "class Main {}"),
        entry("README.md", "# demo"));

    MvcResult imported = mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "demo.zip", "application/zip", zip))
            .param("name", name)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value(name))
        .andExpect(jsonPath("$.sourceType").value("ZIP_UPLOAD"))
        .andExpect(jsonPath("$.fileCount").value(2))
        .andExpect(jsonPath("$.storageRef").doesNotExist())
        .andReturn();
    String id = objects.readTree(imported.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(get("/api/v1/projects/" + id + "/files")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].sha256").isString());

    mockMvc.perform(get("/api/v1/projects/" + id + "/files/content")
            .param("path", "src/Main.java")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("class Main {}"))
        .andExpect(jsonPath("$.truncated").value(false));

    assertThat(auditLogs.findAll().stream()
        .anyMatch(row -> "PROJECT_ZIP_IMPORTED".equals(row.getAction()))).isTrue();
  }

  @Test
  void traversalAbsoluteAndConflictEntriesAreRejected() throws Exception {
    String token = access(register());
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "evil.zip", "application/zip", zipOf(entry("../evil.txt", "x"))))
            .param("name", "traversal-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("zip_slip"));
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "evil.zip", "application/zip", zipOf(entry("/tmp/evil.txt", "x"))))
            .param("name", "absolute-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("zip_slip"));
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "evil.zip", "application/zip", zipOf(entry("a", "file"), entry("a/b.txt", "nested"))))
            .param("name", "conflict-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("zip_slip"));
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "evil.zip", "application/zip", zipOf(entry("..\\evil.txt", "x"))))
            .param("name", "backslash-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("zip_slip"));
  }

  @Test
  void nonZipUploadsAreRejected() throws Exception {
    String token = access(register());
    // Wrong magic bytes with a .zip name.
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "fake.zip", "application/zip",
                "not a zip".getBytes(StandardCharsets.UTF_8)))
            .param("name", "fake-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("corrupt_zip"));
    // Right magic, wrong extension.
    byte[] zip = zipOf(entry("a.txt", "x"));
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "demo.txt", "application/zip", zip))
            .param("name", "fake-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("invalid_extension"));
    // Empty archive.
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "empty.zip", "application/zip", zipOf()))
            .param("name", "empty-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("empty_archive"));
  }

  @Test
  void fileCountCapIsEnforced() throws Exception {
    String token = access(register());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (int i = 0; i < 50001; i++) {
        zip.putNextEntry(new ZipEntry("f" + i + ".txt"));
        zip.write('x');
        zip.closeEntry();
      }
    }
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "many.zip", "application/zip",
                bytes.toByteArray()))
            .param("name", "many-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("too_many_files"));
  }

  @Test
  void oversizedZipIsRejectedWith422() throws Exception {
    String token = access(register());
    // Create a zip with a file that's just over 1GB (but under multipart limit)
    // Note: compressed zeros are small, so this hits the single-file limit during extraction
    byte[] big = new byte[1025 * 1024 * 1024];
    byte[] zip = zipOf(entry("big.bin", big));
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "big.zip", "application/zip", zip))
            .param("name", "big-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("file_too_large"));
  }

  @Test
  void zipWithNodeModulesIgnored() throws Exception {
    String token = access(register());
    String name = "node-modules-" + UUID.randomUUID();
    byte[] zip = zipOf(
        entry("src/Main.java", "class Main {}"),
        entry("node_modules/some-package/index.js", "module.exports = {}"),
        entry("node_modules/.cache/file", "cache"),
        entry("dist/bundle.js", "bundled"),
        entry(".git/config", "git config"),
        entry("target/classes/Main.class", "compiled"),
        entry("build/output.txt", "build output"),
        entry(".next/static/chunks/main.js", "nextjs"),
        entry(".venv/lib/python3.11/site-packages/pkg/__init__.py", "venv"),
        entry("venv/lib/python3.11/site-packages/pkg/__init__.py", "venv2"),
        entry("__pycache__/module.cpython-311.pyc", "pycache"),
        entry(".idea/workspace.xml", "idea"),
        entry(".gradle/gradle.properties", "gradle"),
        entry(".mypy_cache/foo", "mypy")
    );
    MvcResult imported = mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "demo.zip", "application/zip", zip))
            .param("name", name)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value(name))
        .andExpect(jsonPath("$.fileCount").value(1)) // Only src/Main.java should be counted
        .andReturn();
    String id = objects.readTree(imported.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(get("/api/v1/projects/" + id + "/files")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].path").value("src/Main.java"));
  }

  @Test
  void corruptZipIsRejected() throws Exception {
    String token = access(register());
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "corrupt.zip", "application/zip",
                "not a zip".getBytes(StandardCharsets.UTF_8)))
            .param("name", "corrupt-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("corrupt_zip"));
  }

  @Test
  void oversizedSingleFileIsRejected() throws Exception {
    String token = access(register());
    byte[] big = new byte[11 * 1024 * 1024];
    byte[] zip = zipOf(entry("big.bin", big));
    mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "big.zip", "application/zip", zip))
            .param("name", "big-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("file_too_large"));
  }

  @Test
  void binaryAndMissingFilesAreHandled() throws Exception {
    String token = access(register());
    String name = "bin-" + UUID.randomUUID();
    byte[] zip = zipOf(entry("blob.bin", new byte[]{1, 2, 0, 3}));
    MvcResult imported = mockMvc.perform(multipart("/api/v1/projects/import/zip/sync")
            .file(new MockMultipartFile("file", "bin.zip", "application/zip", zip))
            .param("name", name)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andReturn();
    String id = objects.readTree(imported.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(get("/api/v1/projects/" + id + "/files/content")
            .param("path", "blob.bin")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/v1/projects/" + id + "/files/content")
            .param("path", "../other.txt")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/v1/projects/" + id + "/files/content")
            .param("path", "missing.txt")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  private void assertRejected(String token, byte[] zip, String name) throws Exception {
    mockMvc.perform(multipart("/api/v1/projects/import/zip")
            .file(new MockMultipartFile("file", "evil.zip", "application/zip", zip))
            .param("name", name + "-" + UUID.randomUUID())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  private String register() throws Exception {
    String email = "ingest-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Ing\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }

  private static ZipEntryData entry(String name, String content) {
    return new ZipEntryData(name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static ZipEntryData entry(String name, byte[] content) {
    return new ZipEntryData(name, content);
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
  }

  @Test
  void importLimitsEndpointReturnsConfiguredLimits() throws Exception {
    mockMvc.perform(get("/api/v1/projects/import/limits"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxZipBytes").value(157286400))
        .andExpect(jsonPath("$.maxFiles").value(50000))
        .andExpect(jsonPath("$.maxTotalUncompressedBytes").value(524288000L))
        .andExpect(jsonPath("$.maxSingleFileBytes").value(10485760))
        .andExpect(jsonPath("$.maxZipBytesHuman").value("150 MB"))
        .andExpect(jsonPath("$.maxTotalUncompressedBytesHuman").value("500 MB"))
        .andExpect(jsonPath("$.maxSingleFileBytesHuman").value("10 MB"));
  }

  @Test
  void importLimitsEndpointIsPublic() throws Exception {
    // No Authorization header - should still work
    mockMvc.perform(get("/api/v1/projects/import/limits"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxZipBytes").exists());
  }

  @Test
  void asyncZipImportReturnsJobIdAndStatus() throws Exception {
    String token = access(register());
    byte[] zipBytes = new byte[] {(byte) 0x50, (byte) 0x4B, 0x03, 0x04}; // PK\x03\x04
    MockMultipartFile zipFile = new MockMultipartFile("file", "test.zip", "application/zip", zipBytes);

    mockMvc.perform(multipart("/api/v1/projects/import/zip")
            .file(zipFile)
            .param("name", "async-import-test")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").exists())
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  @Test
  void asyncGitHubImportReturnsJobIdAndStatus() throws Exception {
    String token = access(register());

    mockMvc.perform(post("/api/v1/projects/import/github")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + token)
            .content("{\"name\":\"github-import-test\",\"url\":\"https://github.com/octocat/Hello-World\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").exists())
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }
}
