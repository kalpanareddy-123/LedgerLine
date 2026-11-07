package com.snovasys.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.AccountStatus;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.CreateAccountRequest;
import com.snovasys.ledgerline.dto.CreateUserRequest;
import com.snovasys.ledgerline.dto.UpdateAccountStatusRequest;
import com.snovasys.ledgerline.repository.AccountRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountAndAdminTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User adminUser;
    private User holderAlice;
    private User holderBob;
    private User auditorUser;

    private Account aliceAccount;
    private Account bobAccount;

    private String adminToken;
    private String aliceToken;
    private String bobToken;
    private String auditorToken;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        adminUser = userRepository.save(new User("admin_" + suffix, "admin_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ADMIN));
        holderAlice = userRepository.save(new User("alice_" + suffix, "alice_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        holderBob = userRepository.save(new User("bob_" + suffix, "bob_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        auditorUser = userRepository.save(new User("auditor_" + suffix, "auditor_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.AUDITOR));

        aliceAccount = accountRepository.save(new Account(holderAlice.getId(), "ACC-ALICE-" + suffix, 0L, false));
        bobAccount = accountRepository.save(new Account(holderBob.getId(), "ACC-BOB-" + suffix, 0L, false));

        adminToken = jwtService.generateToken(adminUser.getId(), adminUser.getUsername(), adminUser.getEmail(), adminUser.getRole());
        aliceToken = jwtService.generateToken(holderAlice.getId(), holderAlice.getUsername(), holderAlice.getEmail(), holderAlice.getRole());
        bobToken = jwtService.generateToken(holderBob.getId(), holderBob.getUsername(), holderBob.getEmail(), holderBob.getRole());
        auditorToken = jwtService.generateToken(auditorUser.getId(), auditorUser.getUsername(), auditorUser.getEmail(), auditorUser.getRole());
    }

    @Test
    @DisplayName("Admin can create new user via POST /api/admin/users")
    void adminCanCreateUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        CreateUserRequest req = new CreateUserRequest("charlie_" + suffix, "charlie_" + suffix + "@test.com", "Pass123!", Role.ACCOUNT_HOLDER);

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("charlie_" + suffix))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));
    }

    @Test
    @DisplayName("Non-admin cannot create user (returns 403 ACCESS_DENIED)")
    void nonAdminCannotCreateUser() throws Exception {
        CreateUserRequest req = new CreateUserRequest("testuser", "test@test.com", "Pass123!", Role.ACCOUNT_HOLDER);

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Admin can create account and freeze it")
    void adminCanCreateAndFreezeAccount() throws Exception {
        CreateAccountRequest createReq = new CreateAccountRequest(holderAlice.getId(), null, 0L);

        String responseBody = mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String accountIdStr = objectMapper.readTree(responseBody).get("id").asText();
        UUID accountId = UUID.fromString(accountIdStr);

        // Freeze account
        UpdateAccountStatusRequest updateReq = new UpdateAccountStatusRequest(AccountStatus.FROZEN);
        mockMvc.perform(patch("/api/admin/accounts/" + accountId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        Account frozenAcc = accountRepository.findById(accountId).get();
        assertThat(frozenAcc.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("Ownership check: Alice viewing Bob's account gets 404 (not 403, preventing existence leak)")
    void aliceViewingBobAccountGets404() throws Exception {
        mockMvc.perform(get("/api/accounts/" + bobAccount.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Alice viewing her own account succeeds with 200 OK")
    void aliceViewingOwnAccountSucceeds() throws Exception {
        mockMvc.perform(get("/api/accounts/" + aliceAccount.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceAccount.getId().toString()))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @DisplayName("Auditor and Admin can view any user account")
    void auditorAndAdminCanViewAnyAccount() throws Exception {
        // Auditor views Alice's account
        mockMvc.perform(get("/api/accounts/" + aliceAccount.getId())
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceAccount.getId().toString()));

        // Admin views Bob's account
        mockMvc.perform(get("/api/accounts/" + bobAccount.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bobAccount.getId().toString()));
    }
}
