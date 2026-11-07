package com.snovasys.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.Account;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrencyAndConservationTest {

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
    private LedgerService ledgerService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private User admin;
    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        admin = userRepository.save(new User("admin_conc_" + suffix, "admin_conc_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ADMIN));
        alice = userRepository.save(new User("alice_conc_" + suffix, "alice_conc_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        bob = userRepository.save(new User("bob_conc_" + suffix, "bob_conc_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));

        aliceAccount = accountRepository.save(new Account(alice.getId(), "ACC-C-A-" + suffix, 0L, false));
        bobAccount = accountRepository.save(new Account(bob.getId(), "ACC-C-B-" + suffix, 0L, false));

        aliceToken = jwtService.generateToken(alice.getId(), alice.getUsername(), alice.getEmail(), alice.getRole());
        bobToken = jwtService.generateToken(bob.getId(), bob.getUsername(), bob.getEmail(), bob.getRole());
    }

    @Test
    @DisplayName("Concurrency: 50 parallel withdrawals on limited funds - final balance never negative, exactly affordable succeed")
    void fiftyParallelWithdrawalsLimitedFunds() throws Exception {
        // Initial deposit of 10,000 paise (Rs 100.00)
        com.snovasys.ledgerline.security.UserPrincipal adminPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(admin.getId(), admin.getUsername(), admin.getEmail(), Role.ADMIN, true);
        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(10000L, "Initial funding"), UUID.randomUUID().toString(), adminPrincipal);

        int threads = 50;
        long withdrawalAmount = 500L; // 500 paise each -> exactly 20 withdrawals possible (10000 / 500 = 20)
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    WithdrawRequest req = new WithdrawRequest(withdrawalAmount, "Parallel withdrawal");
                    MvcResult result = mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/withdraw")
                                    .header("Authorization", "Bearer " + aliceToken)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if ((status == 422 || status == 400) && result.getResponse().getContentAsString().contains("INSUFFICIENT_FUNDS")) {
                        insufficientFundsCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        latch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(20);
        assertThat(insufficientFundsCount.get()).isEqualTo(30);

        Account finalAccount = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        assertThat(finalAccount.getBalance()).isEqualTo(0L);
        assertThat(finalAccount.getBalance()).isGreaterThanOrEqualTo(0L);

        // Verify account ledger matches balance
        Long ledgerSum = ledgerEntryRepository.calculateAccountLedgerSum(aliceAccount.getId());
        assertThat(ledgerSum).isEqualTo(0L);
    }

    @Test
    @DisplayName("Concurrency: Parallel opposite transfers A->B and B->A complete without deadlocks (ordered locks)")
    void parallelOppositeTransfersNoDeadlocks() throws Exception {
        com.snovasys.ledgerline.security.UserPrincipal adminPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(admin.getId(), admin.getUsername(), admin.getEmail(), Role.ADMIN, true);
        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(50000L, "Seed Alice"), UUID.randomUUID().toString(), adminPrincipal);
        ledgerService.deposit(bobAccount.getId(), new DepositRequest(50000L, "Seed Bob"), UUID.randomUUID().toString(), adminPrincipal);

        int pairs = 20; // 20 A->B and 20 B->A = 40 parallel transfers
        int totalThreads = pairs * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successfulTransfers = new AtomicInteger(0);

        for (int i = 0; i < pairs; i++) {
            // A -> B
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferRequest req = new TransferRequest(aliceAccount.getId(), bobAccount.getId(), 100L, "A to B");
                    MvcResult res = mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + aliceToken)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 200) {
                        successfulTransfers.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });

            // B -> A
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferRequest req = new TransferRequest(bobAccount.getId(), aliceAccount.getId(), 100L, "B to A");
                    MvcResult res = mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + bobToken)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 200) {
                        successfulTransfers.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successfulTransfers.get()).isEqualTo(totalThreads);

        Account finalAlice = accountRepository.findById(aliceAccount.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bobAccount.getId()).orElseThrow();

        // 20 transferred out (-2000), 20 transferred in (+2000) -> net change = 0
        assertThat(finalAlice.getBalance()).isEqualTo(50000L);
        assertThat(finalBob.getBalance()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("Conservation: After 50 random operations, sum of all entries = 0 and each balance = sum of entries")
    void conservationAfterRandomOperations() throws Exception {
        com.snovasys.ledgerline.security.UserPrincipal adminPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(admin.getId(), admin.getUsername(), admin.getEmail(), Role.ADMIN, true);
        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(100000L, "Initial funding"), UUID.randomUUID().toString(), adminPrincipal);
        ledgerService.deposit(bobAccount.getId(), new DepositRequest(100000L, "Initial funding"), UUID.randomUUID().toString(), adminPrincipal);

        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            int op = random.nextInt(3);
            long amount = (random.nextInt(20) + 1) * 100L; // 100 to 2000 paise

            if (op == 0) {
                // Deposit to Alice or Bob
                boolean toAlice = random.nextBoolean();
                UUID targetId = toAlice ? aliceAccount.getId() : bobAccount.getId();
                String token = toAlice ? aliceToken : bobToken;
                DepositRequest req = new DepositRequest(amount, "Random Deposit " + i);
                mockMvc.perform(post("/api/accounts/" + targetId + "/deposit")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status -> assertThat(status.getResponse().getStatus()).isIn(200, 400, 422));
            } else if (op == 1) {
                // Transfer between Alice and Bob
                boolean aToB = random.nextBoolean();
                UUID srcId = aToB ? aliceAccount.getId() : bobAccount.getId();
                UUID dstId = aToB ? bobAccount.getId() : aliceAccount.getId();
                String token = aToB ? aliceToken : bobToken;
                TransferRequest req = new TransferRequest(srcId, dstId, amount, "Random Transfer " + i);
                mockMvc.perform(post("/api/transfers")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status -> assertThat(status.getResponse().getStatus()).isIn(200, 400, 422));
            } else {
                // Withdraw from Alice or Bob
                boolean fromAlice = random.nextBoolean();
                UUID targetId = fromAlice ? aliceAccount.getId() : bobAccount.getId();
                String token = fromAlice ? aliceToken : bobToken;
                WithdrawRequest req = new WithdrawRequest(amount, "Random Withdraw " + i);
                mockMvc.perform(post("/api/accounts/" + targetId + "/withdraw")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status -> assertThat(status.getResponse().getStatus()).isIn(200, 400, 422));
            }
        }

        // Global conservation invariant: SUM of all ledger entries in DB MUST strictly equal 0
        Long totalLedgerSum = ledgerEntryRepository.calculateTotalLedgerSum();
        assertThat(totalLedgerSum).isEqualTo(0L);

        // Account conservation invariant: each account's balance MUST equal sum of its ledger entries
        List<Account> allAccounts = accountRepository.findAll();
        for (Account acc : allAccounts) {
            Long entrySum = ledgerEntryRepository.calculateAccountLedgerSum(acc.getId());
            assertThat(acc.getBalance())
                    .as("Account " + acc.getAccountNumber() + " balance must equal sum of its ledger entries")
                    .isEqualTo(entrySum);
        }
    }
}
