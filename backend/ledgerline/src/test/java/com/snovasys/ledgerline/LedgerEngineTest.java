package com.snovasys.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.AccountStatus;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawRequest;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.LedgerEntryRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.security.JwtService;
import com.snovasys.ledgerline.service.LedgerService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LedgerEngineTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private LedgerService ledgerService;

    private User alice;
    private User bob;
    private User auditor;

    private Account aliceAccount;
    private Account bobAccount;
    private Account frozenAccount;

    private String aliceToken;
    private String bobToken;
    private String auditorToken;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        alice = userRepository.save(new User("alice_tx_" + suffix, "alice_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        bob = userRepository.save(new User("bob_tx_" + suffix, "bob_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        auditor = userRepository.save(new User("auditor_tx_" + suffix, "auditor_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.AUDITOR));

        aliceAccount = accountRepository.save(new Account(alice.getId(), "ACC-A-" + suffix, 0L, false));
        bobAccount = accountRepository.save(new Account(bob.getId(), "ACC-B-" + suffix, 0L, false));
        frozenAccount = accountRepository.save(new Account(alice.getId(), "ACC-F-" + suffix, 0L, false));

        User admin = userRepository.save(new User("admin_tx_" + suffix, "admin_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ADMIN));
        com.snovasys.ledgerline.security.UserPrincipal adminPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(admin.getId(), admin.getUsername(), admin.getEmail(), Role.ADMIN, true);
        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(50000L, "Seed Alice"), UUID.randomUUID().toString(), adminPrincipal);
        ledgerService.deposit(bobAccount.getId(), new DepositRequest(20000L, "Seed Bob"), UUID.randomUUID().toString(), adminPrincipal);
        ledgerService.deposit(frozenAccount.getId(), new DepositRequest(10000L, "Seed Frozen"), UUID.randomUUID().toString(), adminPrincipal);

        frozenAccount = accountRepository.findById(frozenAccount.getId()).orElseThrow();
        frozenAccount.setStatus(AccountStatus.FROZEN);
        frozenAccount = accountRepository.save(frozenAccount);

        aliceToken = jwtService.generateToken(alice.getId(), alice.getUsername(), alice.getEmail(), alice.getRole());
        bobToken = jwtService.generateToken(bob.getId(), bob.getUsername(), bob.getEmail(), bob.getRole());
        auditorToken = jwtService.generateToken(auditor.getId(), auditor.getUsername(), auditor.getEmail(), auditor.getRole());
    }

    @Test
    @DisplayName("Deposit increases account balance and double-entry conservation holds")
    void depositSuccess() throws Exception {
        DepositRequest req = new DepositRequest(15000L, "Salary deposit");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Account refreshedAlice = accountRepository.findById(aliceAccount.getId()).get();
        assertThat(refreshedAlice.getBalance()).isEqualTo(65000L);

        // Verify total ledger conservation sum is 0
        Long totalSum = ledgerEntryRepository.calculateTotalLedgerSum();
        assertThat(totalSum).isEqualTo(0L);
    }

    @Test
    @DisplayName("Withdrawal decreases account balance and double-entry conservation holds")
    void withdrawSuccess() throws Exception {
        WithdrawRequest req = new WithdrawRequest(10000L, "ATM cash");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.amount").value(10000));

        Account refreshedAlice = accountRepository.findById(aliceAccount.getId()).get();
        assertThat(refreshedAlice.getBalance()).isEqualTo(40000L);

        Long totalSum = ledgerEntryRepository.calculateTotalLedgerSum();
        assertThat(totalSum).isEqualTo(0L);
    }

    @Test
    @DisplayName("Insufficient funds withdrawal returns 422 INSUFFICIENT_FUNDS")
    void withdrawInsufficientFunds() throws Exception {
        WithdrawRequest req = new WithdrawRequest(100000L, "Huge withdraw"); // balance is only 50000
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        Account refreshedAlice = accountRepository.findById(aliceAccount.getId()).get();
        assertThat(refreshedAlice.getBalance()).isEqualTo(50000L); // unchanged
    }

    @Test
    @DisplayName("Transfer between Alice and Bob updates both balances and preserves double-entry zero sum")
    void transferSuccess() throws Exception {
        TransferRequest req = new TransferRequest(aliceAccount.getId(), bobAccount.getId(), 25000L, "Rent payment");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.amount").value(25000));

        Account refreshedAlice = accountRepository.findById(aliceAccount.getId()).get();
        Account refreshedBob = accountRepository.findById(bobAccount.getId()).get();

        assertThat(refreshedAlice.getBalance()).isEqualTo(25000L);
        assertThat(refreshedBob.getBalance()).isEqualTo(45000L);

        Long totalSum = ledgerEntryRepository.calculateTotalLedgerSum();
        assertThat(totalSum).isEqualTo(0L);
    }

    @Test
    @DisplayName("Self-transfer is rejected with 400 INVALID_REQUEST")
    void selfTransferRejected() throws Exception {
        TransferRequest req = new TransferRequest(aliceAccount.getId(), aliceAccount.getId(), 5000L, "Self transfer");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("Frozen account operations are rejected with 422 ACCOUNT_FROZEN")
    void frozenAccountRejected() throws Exception {
        DepositRequest req = new DepositRequest(5000L, "Deposit to frozen");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + frozenAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FROZEN"));
    }

    @Test
    @DisplayName("Auditor is forbidden from moving money (403 ACCESS_DENIED)")
    void auditorCannotMoveMoney() throws Exception {
        DepositRequest req = new DepositRequest(5000L, "Auditor deposit");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + auditorToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Holder cannot withdraw or transfer from another user's account (404 NOT_FOUND)")
    void holderCannotSpendFromAnotherAccount() throws Exception {
        // Alice attempts to withdraw from Bob's account
        WithdrawRequest req = new WithdrawRequest(5000L, "Theft attempt");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + bobAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
