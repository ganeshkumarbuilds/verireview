package com.verireview.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 9A fix-request foundation (API_DESIGN §2): explicit approval gate,
 * one-open-per-finding, owner authorization, audit trail, DTO shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FixRequestTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Autowired
  private AuditLogRepository auditLogs;

  @Autowired
  private UserRepository users;

  @Autowired
  private ProjectRepository projects;

  @Autowired
  private ReviewRepository reviews;

  @Autowired
  private FindingRepository findings;

  @Test
  void createGetAndHistory() throws Exception {
    String token = access(register());
    String findingId = findingFor(tokenOwnerOf(token));

    MvcResult created = mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scopeNote\":\"fix the null check\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.findingId").value(findingId))
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.scopeNote").value("fix the null check"))
        .andExpect(jsonPath("$.projectId").isString())
        .andExpect(jsonPath("$.requestedBy").isString())
        .andExpect(jsonPath("$.finding.passwordHash").doesNotExist())
        .andReturn();
    String fixId =
        objects.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict());

    mockMvc.perform(get("/api/v1/fix-requests/" + fixId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(fixId))
        .andExpect(jsonPath("$.status").value("REQUESTED"));

    mockMvc.perform(get("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    assertThat(auditLogs.findAll().stream()
        .anyMatch(row -> "FIX_REQUEST_CREATED".equals(row.getAction()))).isTrue();
  }

  @Test
  void nonOwnerGets404Everywhere() throws Exception {
    String ownerToken = access(register());
    String otherToken = access(register());
    String findingId = findingFor(tokenOwnerOf(ownerToken));

    mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());

    MvcResult created = mockMvc.perform(post("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isAccepted())
        .andReturn();
    String fixId =
        objects.readTree(created.getResponse().getContentAsString()).get("id").asText();

    mockMvc.perform(get("/api/v1/fix-requests/" + fixId)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/findings/" + findingId + "/fix-requests")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void unknownIdsAndMissingAuth() throws Exception {
    String token = access(register());
    String randomFinding = UUID.randomUUID().toString();
    String randomFix = UUID.randomUUID().toString();

    mockMvc.perform(post("/api/v1/findings/" + randomFinding + "/fix-requests")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/fix-requests/" + randomFix)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/v1/findings/" + randomFinding + "/fix-requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/fix-requests/" + randomFix))
        .andExpect(status().isUnauthorized());
  }

  private String findingFor(User owner) {
    Project project = projects.save(
        new Project(owner, "fix-" + UUID.randomUUID(), ProjectSourceType.PASTE));
    Review review = reviews.save(new Review(project));
    Finding finding = new Finding(
        review, FindingCategory.SECURITY, FindingSeverity.HIGH,
        FindingSource.DETERMINISTIC, "SQL concat");
    return findings.save(finding).getId().toString();
  }

  private User tokenOwnerOf(String token) throws Exception {
    MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    String email =
        objects.readTree(me.getResponse().getContentAsString()).get("email").asText();
    return users.findByEmail(email).orElseThrow();
  }

  private String register() throws Exception {
    String email = "fix-" + UUID.randomUUID() + "@example.com";
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email
                + "\",\"password\":\"correct-horse-99!\",\"displayName\":\"Fix\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  private String access(String registrationBody) throws Exception {
    return objects.readTree(registrationBody).get("accessToken").asText();
  }
}
