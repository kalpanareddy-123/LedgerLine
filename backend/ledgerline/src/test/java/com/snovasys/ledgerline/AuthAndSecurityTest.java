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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
    @DisplayName("JwtService fails outside dev/test profile if JWT_SECRET is missing, <32 bytes, or known default")
    void jwtServiceFailsFastInProduction() {
        org.springframework.mock.env.MockEnvironment prodEnv = new org.springframework.mock.env.MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        // Missing secret
        assertThatThrownBy(() -> new JwtService("", 1800000, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");

        // Short secret (< 32 bytes)
        assertThatThrownBy(() -> new JwtService("short_secret_under_32_bytes!", 1800000, prodEnv))
                .isInstanceOf(IllegalStateException.class);

        // Known default placeholder
        assertThatThrownBy(() -> new JwtService("ledgerline_super_secure_jwt_secret_key_minimum_256_bits_length_123456", 1800000, prodEnv))
                .isInstanceOf(IllegalStateException.class);

        // Valid 32-byte secret succeeds
        JwtService validProdJwt = new JwtService("a_very_strong_random_secret_key_for_prod_1234567890!", 1800000, prodEnv);
        assertThat(validProdJwt).isNotNull();
    }

    @Test
    @DisplayName("Expired JWT is rejected with 401")
    void expiredTokenIsRejected() throws Exception {
        javax.crypto.SecretKey testKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "test_super_secure_jwt_secret_key_minimum_256_bits_length_123456".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String expiredToken = io.jsonwebtoken.Jwts.builder()
                .subject(testUser.getUsername())
                .claims(java.util.Map.of("userId", testUser.getId().toString(), "role", testUser.getRole().name()))
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 100000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 1000))
                .signWith(testKey)
                .compact();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("JWT with wrong signature is rejected with 401")
    void wrongSignatureTokenIsRejected() throws Exception {
        javax.crypto.SecretKey wrongKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "completely_different_signing_key_for_testing_purposes_9999!".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        String wrongSigToken = io.jsonwebtoken.Jwts.builder()
                .subject(testUser.getUsername())
                .claims(java.util.Map.of("userId", testUser.getId().toString(), "role", testUser.getRole().name()))
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith(wrongKey)
                .compact();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + wrongSigToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("JWT with alg=none is rejected with 401")
    void algNoneTokenIsRejected() throws Exception {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + testUser.getUsername() + "\",\"userId\":\"" + testUser.getId() + "\",\"role\":\"" + testUser.getRole() + "\"}").getBytes()
        );
        String algNoneToken = header + "." + payload + ".";

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + algNoneToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Security headers (nosniff, DENY, strict-origin-when-cross-origin, Cache-Control no-store) present on API responses")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Frame-Options", "DENY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("Every non-public endpoint returns 401 when accessed without a token")
    void nonPublicEndpointsReturn401WithoutToken() throws Exception {
        java.util.UUID dummyId = java.util.UUID.randomUUID();
        String[] getEndpoints = {
                "/api/auth/me",
                "/api/accounts",
                "/api/accounts/" + dummyId,
                "/api/accounts/" + dummyId + "/statement",
                "/api/accounts/" + dummyId + "/statement/csv",
                "/api/admin/users",
                "/api/admin/accounts",
                "/api/audit/logs",
                "/api/audit/reconciliation"
        };

        for (String endpoint : getEndpoints) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        String[] postEndpoints = {
                "/api/accounts/" + dummyId + "/deposit",
                "/api/accounts/" + dummyId + "/withdraw",
                "/api/transfers",
                "/api/admin/users",
                "/api/admin/accounts",
                "/api/admin/accounts/" + dummyId + "/freeze",
                "/api/admin/accounts/" + dummyId + "/unfreeze",
                "/api/admin/accounts/" + dummyId + "/close"
        };

        for (String endpoint : postEndpoints) {
            mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    @DisplayName("Admin creating user with password < 8 characters returns 400 Bad Request")
    void shortPasswordRejected() throws Exception {
        User admin = userRepository.findByUsername("admin_sec_test").orElseGet(() ->
                userRepository.save(new User("admin_sec_test", "admin_sec@test.com", passwordEncoder.encode("AdminPass123!"), Role.ADMIN))
        );
        String adminToken = jwtService.generateToken(admin.getId(), admin.getUsername(), admin.getEmail(), admin.getRole());

        String json = """
                {
                    "username": "short_pw_user",
                    "email": "short_pw@test.com",
                    "password": "pass",
                    "role": "ACCOUNT_HOLDER"
                }
                """;

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 8 characters")));
    }

    @Test
    @DisplayName("Per-IP rate limiter honours X-Forwarded-For safely and locks out 6th bad attempt from that IP")
    void perIpRateLimiterHonoursXForwardedFor() throws Exception {
        String testIp = "203.0.113." + (int)(Math.random() * 200 + 10);
        LoginRequest badReq = new LoginRequest("non_existent_user", "WrongPassword123!");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", testIp + ", 10.0.0.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badReq)))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt from same IP must be 429
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", testIp + ", 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        // A request from a different IP is NOT locked out
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "198.51.100.77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isUnauthorized());
    }
}
