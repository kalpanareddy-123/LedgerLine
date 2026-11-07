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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private User alice;
    private User bob;
    private User auditor;

    private Account aliceAccount;
    private Account bobAccount;
    private Account frozenAccount;
    private Account closedAccount;

    private String aliceToken;
    private String bobToken;
    private String auditorToken;
    private String adminToken;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        alice = userRepository.save(new User("alice_tx_" + suffix, "alice_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        bob = userRepository.save(new User("bob_tx_" + suffix, "bob_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        auditor = userRepository.save(new User("auditor_tx_" + suffix, "auditor_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.AUDITOR));

        aliceAccount = accountRepository.save(new Account(alice.getId(), "ACC-A-" + suffix, 0L, false));
        bobAccount = accountRepository.save(new Account(bob.getId(), "ACC-B-" + suffix, 0L, false));
        frozenAccount = accountRepository.save(new Account(alice.getId(), "ACC-F-" + suffix, 0L, false));
        closedAccount = accountRepository.save(new Account(alice.getId(), "ACC-C-" + suffix, 0L, false));

        User admin = userRepository.save(new User("admin_tx_" + suffix, "admin_tx_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ADMIN));
        adminToken = jwtService.generateToken(admin.getId(), admin.getUsername(), admin.getEmail(), admin.getRole());

        com.snovasys.ledgerline.security.UserPrincipal alicePrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(alice.getId(), alice.getUsername(), alice.getEmail(), Role.ACCOUNT_HOLDER, true);
        com.snovasys.ledgerline.security.UserPrincipal bobPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(bob.getId(), bob.getUsername(), bob.getEmail(), Role.ACCOUNT_HOLDER, true);

        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(50000L, "Seed Alice"), UUID.randomUUID().toString(), alicePrincipal);
        ledgerService.deposit(bobAccount.getId(), new DepositRequest(20000L, "Seed Bob"), UUID.randomUUID().toString(), bobPrincipal);
        ledgerService.deposit(frozenAccount.getId(), new DepositRequest(10000L, "Seed Frozen"), UUID.randomUUID().toString(), alicePrincipal);

        frozenAccount = accountRepository.findById(frozenAccount.getId()).orElseThrow();
        frozenAccount.setStatus(AccountStatus.FROZEN);
        frozenAccount = accountRepository.save(frozenAccount);

        closedAccount = accountRepository.findById(closedAccount.getId()).orElseThrow();
        closedAccount.setStatus(AccountStatus.CLOSED);
        closedAccount = accountRepository.save(closedAccount);

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

    @Test
    @DisplayName("Transaction exceeding max transaction amount returns 400 Bad Request")
    void transactionExceedingMaxAmountReturns400() throws Exception {
        DepositRequest req = new DepositRequest(1_000_000_001L, "Excessive deposit");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exceeds the maximum permissible limit")));
    }

    @Test
    @DisplayName("Forced mid-transfer failure rolls back fully: no ledger rows, no balance change")
    void forcedMidTransferFailureRollsBackFully() {
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        Account freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account freshBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        long initialAliceBal = freshAlice.getBalance();
        long initialBobBal = freshBob.getBalance();
        long initialLedgerCount = ledgerEntryRepository.count();

        assertThatThrownBy(() -> {
            txTemplate.execute(status -> {
                freshAlice.setBalance(freshAlice.getBalance() - 10000);
                accountRepository.save(freshAlice);

                // Simulate mid-transfer crash (e.g., server fault or network drop before commit)
                throw new RuntimeException("Simulated mid-transfer system crash!");
            });
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated mid-transfer system crash!");

        Account aliceAfter = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account bobAfter = accountRepository.findById(bobAccount.getId()).orElseThrow();

        assertThat(aliceAfter.getBalance()).isEqualTo(initialAliceBal);
        assertThat(bobAfter.getBalance()).isEqualTo(initialBobBal);
        assertThat(ledgerEntryRepository.count()).isEqualTo(initialLedgerCount);
    }

    @Test
    @DisplayName("Account balance == sum of own ledger entries after every operation")
    void balanceEqualsSumOfOwnLedgerEntriesAfterEveryOperation() throws Exception {
        Account freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Long calcBal = ledgerEntryRepository.calculateAccountLedgerSum(freshAlice.getId());
        assertThat(freshAlice.getBalance()).isEqualTo(calcBal);

        // Perform deposit
        DepositRequest depReq = new DepositRequest(10000L, "Balance check deposit");
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(depReq)))
                .andExpect(status().isOk());

        freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        calcBal = ledgerEntryRepository.calculateAccountLedgerSum(freshAlice.getId());
        assertThat(freshAlice.getBalance()).isEqualTo(calcBal);

        // Perform transfer to Bob
        TransferRequest xferReq = new TransferRequest(aliceAccount.getId(), bobAccount.getId(), 5000L, "Balance check transfer");
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xferReq)))
                .andExpect(status().isOk());

        freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account freshBob = accountRepository.findById(bobAccount.getId()).orElseThrow();
        assertThat(freshAlice.getBalance()).isEqualTo(ledgerEntryRepository.calculateAccountLedgerSum(freshAlice.getId()));
        assertThat(freshBob.getBalance()).isEqualTo(ledgerEntryRepository.calculateAccountLedgerSum(freshBob.getId()));

        // Perform withdraw
        WithdrawRequest wReq = new WithdrawRequest(2000L, "Balance check withdraw");
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wReq)))
                .andExpect(status().isOk());

        freshAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(freshAlice.getBalance()).isEqualTo(ledgerEntryRepository.calculateAccountLedgerSum(freshAlice.getId()));
    }

    @Test
    @DisplayName("404 (not 403) for non-owned accounts on read, deposit, withdraw, and transfer source")
    void ownership404OnAllAccountEndpoints() throws Exception {
        // 1. Read non-owned account
        mockMvc.perform(get("/api/accounts/" + bobAccount.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 2. Deposit into non-owned account
        DepositRequest dep = new DepositRequest(1000L, "Deposit probe");
        mockMvc.perform(post("/api/accounts/" + bobAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 3. Withdraw from non-owned account
        WithdrawRequest wth = new WithdrawRequest(1000L, "Withdraw probe");
        mockMvc.perform(post("/api/accounts/" + bobAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wth)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 4. Transfer with non-owned source account
        TransferRequest xfer = new TransferRequest(bobAccount.getId(), aliceAccount.getId(), 1000L, "Transfer probe");
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xfer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Auditor gets 403 on write endpoints; admin cannot move money")
    void auditorAndAdminCannotMoveMoney() throws Exception {
        DepositRequest dep = new DepositRequest(1000L, "Test write");
        WithdrawRequest wth = new WithdrawRequest(1000L, "Test write");
        TransferRequest xfer = new TransferRequest(aliceAccount.getId(), bobAccount.getId(), 1000L, "Test write");

        // Auditor cannot deposit, withdraw, or transfer
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + auditorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + auditorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wth)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + auditorToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xfer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Admin cannot deposit, withdraw, or transfer
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dep)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/withdraw")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wth)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(xfer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Validation rejections: zero, negative, closed, nonexistent destination, wrong currency")
    void validationRejections() throws Exception {
        // 1. Zero amount
        DepositRequest zeroReq = new DepositRequest(0L, "Zero");
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zeroReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 2. Negative amount
        DepositRequest negReq = new DepositRequest(-500L, "Negative");
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 3. Closed account
        DepositRequest closedReq = new DepositRequest(1000L, "Closed");
        mockMvc.perform(post("/api/accounts/" + closedAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closedReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));

        // 4. Nonexistent destination
        TransferRequest nonExistentDest = new TransferRequest(aliceAccount.getId(), UUID.randomUUID(), 1000L, "To ghost");
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentDest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 5. Wrong currency: database CHECK constraint chk_account_currency forbids non-INR currency
        assertThatThrownBy(() -> {
            jdbcTemplate.update("INSERT INTO accounts (account_number, currency, balance, status, is_system) VALUES ('ACC-USD-FAIL', 'USD', 0, 'ACTIVE', false)");
        }).hasMessageContaining("chk_account_currency");
    }

    @Test
    @DisplayName("Math.addExact and Math.subtractExact overflow prevention test")
    void mathExactOverflowThrowsArithmeticException() {
        assertThatThrownBy(() -> Math.addExact(Long.MAX_VALUE, 1L))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> Math.subtractExact(Long.MIN_VALUE, 1L))
                .isInstanceOf(ArithmeticException.class);
    }
}
