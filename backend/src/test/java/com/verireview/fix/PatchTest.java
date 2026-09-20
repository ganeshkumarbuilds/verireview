package com.verireview.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.agent.AiServiceException;
import com.verireview.agent.CodingAgent;
import com.verireview.agent.CodingAiClient;
import com.verireview.agent.dto.AiCodingResult;
import com.verireview.audit.AuditLogRepository;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.project.Project;
import com.verireview.project.ProjectRepository;
import com.verireview.project.ProjectSourceType;
import com.verireview.review.Finding;
import com.verireview.review.FindingCategory;
import com.verireview.review.FindingRepository;
import com.verireview.review.FindingSeverity;
import com.verireview.review.FindingSource;
import com.verireview.review.Review;
import com.verireview.review.ReviewRepository;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 9C patch foundation: CodingAgent placeholder, Patch linked to FixRequest+project,
 * owner auth, DTOs, audit, status lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatchTest extends AbstractPersistenceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objects;
  @Autowired private AuditLogRepository auditLogs;
  @Autowired private UserRepository users;
  @Autowired private ProjectRepository projects;
  @Autowired private ReviewRepository reviews;
  @Autowired private FindingRepository findings;
  @Autowired private FixRequestRepository fixRequests;
  @Autowired private PatchRepository patches;
  @Autowired private CodingAgent codingAgent;
  @MockitoBean private CodingAiClient codingAiClient;

  private static final String VALID_DIFF =
      "diff --git a/src/Main.java b/src/Main.java\n"
          + "--- a/src/Main.java\n"
          + "+++ b/src/Main.java\n"
          + "@@ -10,3 +10,4 @@\n"
          + " // placeholder for finding: SQL concat\n"
          + "+// FIX (proposed, not applied): SQL concat\n"
          + " // finding: SQL concat";

  @BeforeEach
  void stubCodingAi() {
    when(codingAiClient.coding(any())).thenAnswer(inv -> {
      var arg = inv.getArgument(0);
      if (arg == null) return null;
      var req = (com.verireview.agent.dto.AiCodingRequest) arg;
      return new AiCodingResult(
          req.fixRequestId(),
          "coding",
          "coding/v1",
          VALID_DIFF,
          1,
          1,
          0,
          "Fix SQL concat via prepared statement",
          "ok");
    });
  }

  @Test
  void codingAgentDoesNotModifyFilesAndReturnsPlaceholder() throws Exception {
    User owner = users.findByEmail(tokenOwnerEmail(access(register()))).orElseThrow();
    Project project = projects.save(new Project(owner, "p-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    Review review = reviews.save(new Review(project));
    Finding finding = findings.save(new Finding(review, FindingCategory.SECURITY, FindingSeverity.HIGH, FindingSource.DETERMINISTIC, "SQL concat"));
    FixRequest fr = fixRequests.save(new FixRequest(finding, owner));

    // Capture file count before
    long beforeCount = patches.count();

    CodingAgent.Proposal proposal = codingAgent.propose(fr);

    assertThat(proposal.diff()).contains("diff --git");
    assertThat(proposal.diff()).contains("FIX");
    assertThat(proposal.diff()).doesNotContain("VERIFIED");
    assertThat(proposal.filesChanged()).isEqualTo(1);
    assertThat(patches.count()).isEqualTo(beforeCount); // not yet persisted
  }

  @Test
  @Transactional
  void proposePatchPersistsWithOwnerAndAudit() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(token, findingId);

    MvcResult result = mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.fixRequestId").value(fixId))
        .andExpect(jsonPath("$.projectId").isString())
        .andExpect(jsonPath("$.diff").isString())
        .andExpect(jsonPath("$.status").value("PROPOSED"))
        .andExpect(jsonPath("$.filesChanged").value(1))
        .andReturn();

    String patchId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
    assertThat(patchId).isNotBlank();

    // GET via fixRequest
    mockMvc.perform(get("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(patchId));

    // GET via patch id
    mockMvc.perform(get("/api/v1/patches/" + patchId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.diff").isString());

    assertThat(auditLogs.findAll().stream().anyMatch(r -> "PATCH_PROPOSED".equals(r.getAction()))).isTrue();

    // Verify FixRequest transitioned to IN_PROGRESS
    FixRequest updated = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(FixRequestStatus.IN_PROGRESS);

    // Verify Patch linked to project
    Patch patch = patches.findById(UUID.fromString(patchId)).orElseThrow();
    assertThat(patch.getProject()).isNotNull();
    assertThat(patch.getProject().getId()).isEqualTo(updated.getFinding().getReview().getProject().getId());
  }

  @Test
  void nonOwnerCannotProposeOrView() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    User owner = tokenOwner(ownerToken);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(ownerToken, findingId);

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    // Owner proposes, then other tries to fetch
    MvcResult result = mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isCreated())
        .andReturn();
    String patchId = objects.readTree(result.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(get("/api/v1/patches/" + patchId)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void secondProposeConflictsWhenAlreadyInProgress() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(token, findingId);

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
  }

  @Test
  void unauthenticatedIs401() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(token, findingId);
    UUID fakePatch = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/patches/" + fakePatch))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aiServiceUnavailableReturnsBadGatewayAndNoPatch() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(token, findingId);

    when(codingAiClient.coding(any()))
        .thenThrow(new AiServiceException(AiServiceException.Kind.UNAVAILABLE, "AI down"));

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadGateway());

    FixRequest fr = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    assertThat(fr.getStatus()).isEqualTo(FixRequestStatus.REQUESTED);
    assertThat(patches.findByFixRequestIdOrderByCreatedAtDesc(UUID.fromString(fixId))).isEmpty();
  }

  @Test
  void malformedAiDiffReturnsBadGateway() throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(token, findingId);

    when(codingAiClient.coding(any()))
        .thenReturn(new AiCodingResult(fixId, "coding", "coding/v1", "not a diff", 0, 0, 0, "bad", "notes"));

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadGateway());
  }

  @Test
  void traversalDiffReturnsBadGatewayAndNoPatch() throws Exception {
    assertAttackerDiffRejected(diffWithPaths("../evil.sh"));
    assertAttackerDiffRejected(diffWithPaths("src/../../etc/cron"));
  }

  @Test
  void absoluteDiffReturnsBadGatewayAndNoPatch() throws Exception {
    assertAttackerDiffRejected(diffWithPaths("/etc/passwd"));
    assertAttackerDiffRejected(diffWithPaths("C:/Windows/evil"));
  }

  @Test
  void excessiveDiffReturnsBadGatewayAndNoPatch() throws Exception {
    // Too many files (limit 5)
    StringBuilder manyFiles = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      manyFiles.append("diff --git a/F").append(i).append(".java b/F").append(i).append(".java\n")
          .append("--- a/F").append(i).append(".java\n")
          .append("+++ b/F").append(i).append(".java\n")
          .append("@@ -1 +1 @@\n-x\n+y\n");
    }
    assertAttackerDiffRejected(manyFiles.toString());

    // Too many changed lines (limit 200)
    StringBuilder manyLines = new StringBuilder(
        "diff --git a/Big.java b/Big.java\n--- a/Big.java\n+++ b/Big.java\n@@ -1 +1 @@\n");
    for (int i = 0; i < 201; i++) {
      manyLines.append("+line ").append(i).append('\n');
    }
    assertAttackerDiffRejected(manyLines.toString());
  }

  private void assertAttackerDiffRejected(String attackerDiff) throws Exception {
    String token = access(register());
    User owner = tokenOwner(token);
    String findingId = findingFor(owner);
    String fixId = createFixRequest(token, findingId);

    when(codingAiClient.coding(any()))
        .thenReturn(new AiCodingResult(fixId, "coding", "coding/v1", attackerDiff, 1, 1, 0, "evil", ""));

    mockMvc.perform(post("/api/v1/fix-requests/" + fixId + "/patch")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadGateway());

    // Nothing persisted, FixRequest untouched (still REQUESTED, never applied/executed/verified)
    assertThat(patches.findByFixRequestIdOrderByCreatedAtDesc(UUID.fromString(fixId))).isEmpty();
    FixRequest fr = fixRequests.findById(UUID.fromString(fixId)).orElseThrow();
    assertThat(fr.getStatus()).isEqualTo(FixRequestStatus.REQUESTED);
  }

  private static String diffWithPaths(String path) {
    return "diff --git a/" + path + " b/" + path + "\n"
        + "--- a/" + path + "\n"
        + "+++ b/" + path + "\n"
        + "@@ -1 +1 @@\n"
        + "+x";
  }

  // Helpers
  private String findingFor(User owner) {
    Project project = projects.save(new Project(owner, "fix-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(review, FindingCategory.SECURITY, FindingSeverity.HIGH, FindingSource.DETERMINISTIC, "SQL concat");
    finding.setFilePath("src/Main.java");
    finding.setLineStart(10);
    return findings.save(finding).getId().toString();
  }

  private String createFixRequest(String token, String findingId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private User tokenOwner(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    String email = objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }

  private String tokenOwnerEmail(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andReturn();
    return objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
  }

  private String register() throws Exception {
    String email = "patch-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Patch\"}"))
        .andExpect(status().isCreated()).andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
