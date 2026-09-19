package com.verireview.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.audit.AuditLogRepository;
import com.verireview.persistence.AbstractPersistenceTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 auth happy paths + validation negatives + audit trail, per
 * API_DESIGN §2 and the roadmap verification (full auth flow, audit rows).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Autowired
  private AuditLogRepository auditLogs;

  @Test
  void registerReturns201WithTokensAndMeResolvesProfile() throws Exception {
    JsonNode tokens = register("flow-one@example.com", "correct-horse-99!");

    MvcResult me = mockMvc.perform(
            get("/api/v1/auth/me").header("Authorization", "Bearer " + access(tokens)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("flow-one@example.com"))
        .andExpect(jsonPath("$.roles").isArray())
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.password_hash").doesNotExist())
        .andReturn();
    JsonNode profile = objects.readTree(me.getResponse().getContentAsString());
    assert profile.get("roles").toString().contains("USER");
  }

  @Test
  void duplicateRegistrationIs409() throws Exception {
    register("flow-dup@example.com", "correct-horse-99!");
    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerJson("flow-dup@example.com", "another-horse-99!")))
        .andExpect(status().isConflict());
  }

  @Test
  void weakPasswordsAre400() throws Exception {
    // Too short (Bean Validation).
    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerJson("flow-short@example.com", "short1")))
        .andExpect(status().isBadRequest());
    // Long enough but on the breached denylist (service policy).
    mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerJson("flow-breached@example.com", "password1234")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void eightCharPasswordRegisters() throws Exception {
    register("flow-eight@example.com", "s3cur3p@");
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("flow-eight@example.com", "s3cur3p@")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isString());
  }

  @Test
  void passwordResetRequestIsAlwaysAccepted() throws Exception {
    register("flow-reset@example.com", "correct-horse-99!");
    // Known account → 202 + audit row.
    mockMvc.perform(post("/api/v1/auth/password-reset/request")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"flow-reset@example.com\"}"))
        .andExpect(status().isAccepted());
    // Unknown account → still 202 (no enumeration), no user-bound row.
    mockMvc.perform(post("/api/v1/auth/password-reset/request")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ghost-" + System.nanoTime() + "@example.com\"}"))
        .andExpect(status().isAccepted());
    // Invalid email → 400.
    mockMvc.perform(post("/api/v1/auth/password-reset/request")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest());
    assert auditLogs.findAll().stream()
        .anyMatch(row -> "PASSWORD_RESET_REQUESTED".equals(row.getAction()));
  }

  @Test
  void loginSucceedsThenWrongPasswordIs401() throws Exception {
    register("flow-login@example.com", "correct-horse-99!");
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("flow-login@example.com", "correct-horse-99!")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isString())
        .andExpect(jsonPath("$.refreshToken").isString())
        .andExpect(jsonPath("$.tokenType").value("Bearer"));
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("flow-login@example.com", "wrong-horse-99!!")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("nobody-here@example.com", "correct-horse-99!")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshRotatesAndOldRefreshTokenIsRejected() throws Exception {
    JsonNode first = register("flow-refresh@example.com", "correct-horse-99!");
    JsonNode second = refresh(refresh(first));
    assert !refresh(second).equals(refresh(first));
    // Re-presenting the already-rotated token is reuse → 401.
    mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refresh(first) + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutInvalidatesTheRefreshToken() throws Exception {
    JsonNode tokens = register("flow-logout@example.com", "correct-horse-99!");
    mockMvc.perform(post("/api/v1/auth/logout")
            .header("Authorization", "Bearer " + access(tokens))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refresh(tokens) + "\"}"))
        .andExpect(status().isNoContent());
    mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refresh(tokens) + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authActionsWriteAuditRows() throws Exception {
    JsonNode tokens = register("flow-audit@example.com", "correct-horse-99!");
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson("flow-audit@example.com", "correct-horse-99!")))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/v1/auth/logout")
            .header("Authorization", "Bearer " + access(tokens))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refresh(tokens) + "\"}"))
        .andExpect(status().isNoContent());
    assert auditLogs.findAll().stream().anyMatch(row -> "AUTH_REGISTER".equals(row.getAction()));
    assert auditLogs.findAll().stream().anyMatch(row -> "AUTH_LOGIN".equals(row.getAction()));
    assert auditLogs.findAll().stream().anyMatch(row -> "AUTH_LOGOUT".equals(row.getAction()));
  }

  private JsonNode register(String email, String password) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerJson(email, password)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isString())
        .andExpect(jsonPath("$.refreshToken").isString())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode refresh(String refreshToken) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString());
  }

  private static String access(JsonNode tokens) {
    return tokens.get("accessToken").asText();
  }

  private static String refresh(JsonNode tokens) {
    return tokens.get("refreshToken").asText();
  }

  private static String registerJson(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password
        + "\",\"displayName\":\"Flow\"}";
  }

  private static String loginJson(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }
}
