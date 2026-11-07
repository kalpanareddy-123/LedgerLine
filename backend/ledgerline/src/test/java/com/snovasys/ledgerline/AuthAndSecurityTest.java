package com.snovasys.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.LoginRequest;
import com.snovasys.ledgerline.repository.AuditLogRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthAndSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private User testUser;

    @BeforeEach
    void setup() {
        if (!userRepository.existsByUsername("alice_holder")) {
            testUser = new User(
                    "alice_holder",
                    "alice@snovasys.com",
                    passwordEncoder.encode("SecretAlice123!"),
                    Role.ACCOUNT_HOLDER
            );
            testUser = userRepository.save(testUser);
        } else {
            testUser = userRepository.findByUsername("alice_holder").get();
        }
    }

    @Test
    @DisplayName("POST /api/auth/login with valid credentials returns JWT and user info")
    void loginSuccess() throws Exception {
        LoginRequest req = new LoginRequest("alice_holder", "SecretAlice123!");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.username").value("alice_holder"))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).contains("token");
    }

    @Test
    @DisplayName("POST /api/auth/login with bad password returns 401 with standard JSON error")
    void loginBadPasswordReturns401() throws Exception {
        LoginRequest req = new LoginRequest("alice_holder", "WrongPassword!");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Unauthenticated call to GET /api/auth/me returns 401 JSON error")
    void unauthenticatedCallReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Authenticated call to GET /api/auth/me with valid Bearer token succeeds")
    void authenticatedMeEndpointSucceeds() throws Exception {
        String token = jwtService.generateToken(testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getRole());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice_holder"))
                .andExpect(jsonPath("$.email").value("alice@snovasys.com"))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
    }

    @Test
    @DisplayName("Tampered JWT is rejected with 401")
    void tamperedTokenIsRejected() throws Exception {
        String token = jwtService.generateToken(testUser.getId(), testUser.getUsername(), testUser.getEmail(), testUser.getRole());
        String tampered = token + "corrupted";

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Brute force protection: 5 bad attempts locks out user with 429")
    void rateLimiterLockoutAfter5Failures() throws Exception {
        String attackerUsername = "brute_target_" + System.currentTimeMillis();
        // Create user
        User target = new User(attackerUsername, attackerUsername + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER);
        userRepository.save(target);

        LoginRequest badReq = new LoginRequest(attackerUsername, "BadPass");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(badReq)));
        }

        // 6th attempt must be 429 RateLimitException
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }
}
