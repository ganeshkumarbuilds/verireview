package com.verireview.check;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verireview.persistence.AbstractPersistenceTest;
import com.verireview.user.dto.LoginRequest;
import com.verireview.user.dto.RegisterRequest;
import com.verireview.user.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardSummaryEmptyUserTest extends AbstractPersistenceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void dashboardSummaryReturns200ForNewUserWithNoProjects() throws Exception {
    // Register a new user
    RegisterRequest registerRequest = new RegisterRequest(
        "newuser@example.com", "password123", "New User");
    String registerJson = objectMapper.writeValueAsString(registerRequest);

    String registerResponse = mockMvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerJson))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    TokenResponse tokenResponse = objectMapper.readValue(registerResponse, TokenResponse.class);
    String accessToken = tokenResponse.accessToken();

    // Call dashboard summary with the token - should return 200 with empty state
    mockMvc.perform(get("/api/v1/dashboard/summary")
            .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.features").isArray())
        .andExpect(jsonPath("$.openIssuesBySeverity").isArray())
        .andExpect(jsonPath("$.lastRuns").isArray())
        .andExpect(jsonPath("$.trend").isArray());
        // latestHealthScore can be null for empty user
  }
}