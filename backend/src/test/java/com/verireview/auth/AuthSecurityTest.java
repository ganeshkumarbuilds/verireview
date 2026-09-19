package com.verireview.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verireview.common.AuthRateLimitFilter;
import com.verireview.persistence.AbstractPersistenceTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.verireview.user.RoleRepository;
import com.verireview.user.User;
import com.verireview.user.UserRepository;
import com.verireview.user.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 3 attack-path coverage (SECURITY_DESIGN §9): tampered/expired JWTs,
 * refresh-as-access, RBAC on {@code /users}, cross-user reads answering 404
 * (enumeration resistance, T6), disabled accounts, and the auth rate limiter.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthSecurityTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objects;

  @Autowired
  private UserRepository users;

  @Autowired
  private RoleRepository roles;

  @Autowired
  private UserService userService;

  @Value("${app.jwt.secret}")
  private String jwtSecret;

  @Test
  void protectedEndpointsNeedABearerToken() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
  }

  @Test
  void tamperedTokenIs401() throws Exception {
    String access = access(register("sec-tamper@example.com", "correct-horse-99!"));
    String tampered = tamper(access);
    mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIs401() throws Exception {
    JsonNode tokens = register("sec-expired@example.com", "correct-horse-99!");
    UUID userId = userId(tokens);
    String expired = Jwts.builder()
        .subject(userId.toString())
        .claim("email", "sec-expired@example.com")
        .claim("roles", List.of("USER"))
        .claim("type", "access")
        .issuedAt(Date.from(Instant.now().minusSeconds(3600)))
        .expiration(Date.from(Instant.now().minusSeconds(60)))
        .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
        .compact();
    mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
    JsonNode tokens = register("sec-wrongtype@example.com", "correct-horse-99!");
    mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + tokens.get("refreshToken").asText()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void usersListIsAdminOnly() throws Exception {
    JsonNode userTokens = register("sec-user@example.com", "correct-horse-99!");
    mockMvc.perform(get("/api/v1/users")
            .header("Authorization", "Bearer " + access(userTokens)))
        .andExpect(status().isForbidden());

    // Promote a second account to ADMIN directly (role grant is out of scope
    // for the public auth API in Phase 3).
    register("sec-admin@example.com", "correct-horse-99!");
    User admin = users.findWithRolesByEmail("sec-admin@example.com").orElseThrow();
    admin.getRoles().add(roles.findByName("ADMIN").orElseThrow());
    users.save(admin);
    String adminAccess = loginAccess("sec-admin@example.com", "correct-horse-99!");
    mockMvc.perform(get("/api/v1/users")
            .header("Authorization", "Bearer " + adminAccess))
        .andExpect(status().isOk());
  }

  @Test
  void crossUserReadsAnswer404() throws Exception {
    JsonNode aTokens = register("sec-cross-a@example.com", "correct-horse-99!");
    JsonNode bTokens = register("sec-cross-b@example.com", "correct-horse-99!");
    UUID bId = userId(bTokens);
    // Service-level ownership predicate: another user's id → 404, never 403.
    assertThatThrownBy(() -> userService.getAs(userId(aTokens), bId, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    // Owning user reads fine.
    assertThat(userService.getAs(bId, bId, false).email()).isEqualTo("sec-cross-b@example.com");
  }

  @Test
  void disabledAccountCannotLoginOrCall() throws Exception {
    JsonNode tokens = register("sec-disabled@example.com", "correct-horse-99!");
    User user = users.findByEmail("sec-disabled@example.com").orElseThrow();
    user.setEnabled(false);
    users.save(user);
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"sec-disabled@example.com\",\"password\":\"correct-horse-99!\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/auth/me")
            .header("Authorization", "Bearer " + access(tokens)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rateLimiterRejectsWith429AndRetryAfter() throws Exception {
    AuthRateLimitFilter limiter = new AuthRateLimitFilter(2);
    FilterChain chain = mock(FilterChain.class);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRemoteAddr("10.9.9.9");
    MockHttpServletResponse first = new MockHttpServletResponse();
    MockHttpServletResponse second = new MockHttpServletResponse();
    MockHttpServletResponse third = new MockHttpServletResponse();
    limiter.doFilter(request, first, chain);
    limiter.doFilter(request, second, chain);
    limiter.doFilter(request, third, chain);
    assertThat(third.getStatus()).isEqualTo(429);
    assertThat(third.getHeader("Retry-After")).isNotBlank();
    verify(chain, times(1)).doFilter(request, first);
    verify(chain, times(1)).doFilter(request, second);
    verify(chain, times(0)).doFilter(request, third);
  }

  private JsonNode register(String email, String password) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString());
  }

  private String loginAccess(String email, String password) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return objects.readTree(result.getResponse().getContentAsString())
        .get("accessToken").asText();
  }

  private UUID userId(JsonNode tokens) throws Exception {
    MvcResult me = mockMvc.perform(
            get("/api/v1/auth/me").header("Authorization", "Bearer " + access(tokens)))
        .andExpect(status().isOk())
        .andReturn();
    return UUID.fromString(
        objects.readTree(me.getResponse().getContentAsString()).get("id").asText());
  }

  private static String access(JsonNode tokens) {
    return tokens.get("accessToken").asText();
  }

  private static String tamper(String token) {
    int pos = token.length() / 2;
    char replacement = token.charAt(pos) == 'a' ? 'b' : 'a';
    return token.substring(0, pos) + replacement + token.substring(pos + 1);
  }
}
