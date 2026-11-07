package com.snovasys.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.DepositRequest;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class StatementReconciliationAuditTest {

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

    @Autowired
    private com.snovasys.ledgerline.service.LedgerService ledgerService;

    private User alice;
    private User bob;
    private User admin;
    private User auditor;

    private Account aliceAccount;
    private Account bobAccount;

    private String aliceToken;
    private String adminToken;
    private String auditorToken;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        alice = userRepository.save(new User("alice_stm_" + suffix, "alice_stm_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        bob = userRepository.save(new User("bob_stm_" + suffix, "bob_stm_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        admin = userRepository.save(new User("admin_stm_" + suffix, "admin_stm_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ADMIN));
        auditor = userRepository.save(new User("auditor_stm_" + suffix, "auditor_stm_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.AUDITOR));

        aliceAccount = accountRepository.save(new Account(alice.getId(), "ACC-STM-A-" + suffix, 0L, false));
        bobAccount = accountRepository.save(new Account(bob.getId(), "ACC-STM-B-" + suffix, 0L, false));

        com.snovasys.ledgerline.security.UserPrincipal adminPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(admin.getId(), admin.getUsername(), admin.getEmail(), admin.getRole(), true);
        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(10000L, "Seed Alice"), UUID.randomUUID().toString(), adminPrincipal);
        ledgerService.deposit(bobAccount.getId(), new DepositRequest(20000L, "Seed Bob"), UUID.randomUUID().toString(), adminPrincipal);

        aliceToken = jwtService.generateToken(alice.getId(), alice.getUsername(), alice.getEmail(), alice.getRole());
        adminToken = jwtService.generateToken(admin.getId(), admin.getUsername(), admin.getEmail(), admin.getRole());
        auditorToken = jwtService.generateToken(auditor.getId(), auditor.getUsername(), auditor.getEmail(), auditor.getRole());
    }

    @Test
    @DisplayName("Statement endpoint returns paginated ledger entries with running balance_after")
    void statementPaginatedReturnsEntries() throws Exception {
        // Perform a deposit
        DepositRequest req = new DepositRequest(5000L, "Bonus");
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // Get statement
        mockMvc.perform(get("/api/accounts/" + aliceAccount.getId() + "/statement")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(aliceAccount.getAccountNumber()))
                .andExpect(jsonPath("$.currentBalance").value(15000))
                .andExpect(jsonPath("$.entries.content").isArray())
                .andExpect(jsonPath("$.entries.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries.content[0].amount").value(5000))
                .andExpect(jsonPath("$.entries.content[0].balanceAfter").value(15000));
    }

    @Test
    @DisplayName("Statement CSV export returns text/csv format with correct headers and data")
    void statementCsvExportSuccess() throws Exception {
        DepositRequest req = new DepositRequest(4000L, "Export test deposit");
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        MvcResult res = mockMvc.perform(get("/api/accounts/" + aliceAccount.getId() + "/statement/export")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andReturn();

        String csv = res.getResponse().getContentAsString();
        assertThat(csv).contains("Date,Transaction ID,Direction,Amount (Paise),Amount (INR),Balance After (Paise),Balance After (INR)");
        assertThat(csv).contains("CREDIT,4000,40.00");
    }

    @Test
    @DisplayName("Ownership check: Alice accessing Bob's statement gets 404 (not 403)")
    void statementOwnership404Protection() throws Exception {
        mockMvc.perform(get("/api/accounts/" + bobAccount.getId() + "/statement")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Admin reconciliation endpoint reports OK and total ledger sum = 0")
    void adminReconciliationReportsOk() throws Exception {
        mockMvc.perform(get("/api/admin/reconciliation")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.isBalanced").value(true))
                .andExpect(jsonPath("$.totalLedgerSum").value(0))
                .andExpect(jsonPath("$.mismatches").isEmpty());
    }

    @Test
    @DisplayName("Auditor and Admin can query audit log")
    void auditorAndAdminCanQueryAudit() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/audit")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Account holder is forbidden from accessing /api/audit (returns 403 ACCESS_DENIED)")
    void accountHolderForbiddenFromAudit() throws Exception {
        mockMvc.perform(get("/api/audit")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
