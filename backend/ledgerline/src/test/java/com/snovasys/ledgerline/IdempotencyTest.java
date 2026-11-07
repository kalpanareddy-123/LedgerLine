package com.snovasys.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.TransactionRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private com.snovasys.ledgerline.service.LedgerService ledgerService;

    private User alice;
    private User bob;
    private Account aliceAccount;
    private Account bobAccount;
    private String aliceToken;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        alice = userRepository.save(new User("alice_idemp_" + suffix, "alice_idemp_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));
        bob = userRepository.save(new User("bob_idemp_" + suffix, "bob_idemp_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ACCOUNT_HOLDER));

        aliceAccount = accountRepository.save(new Account(alice.getId(), "ACC-A-" + suffix, 0L, false));
        bobAccount = accountRepository.save(new Account(bob.getId(), "ACC-B-" + suffix, 0L, false));

        User admin = userRepository.save(new User("admin_idemp_" + suffix, "admin_idemp_" + suffix + "@test.com", passwordEncoder.encode("Pass123!"), Role.ADMIN));
        com.snovasys.ledgerline.security.UserPrincipal adminPrincipal =
                new com.snovasys.ledgerline.security.UserPrincipal(admin.getId(), admin.getUsername(), admin.getEmail(), Role.ADMIN, true);
        ledgerService.deposit(aliceAccount.getId(), new DepositRequest(100000L, "Seed Alice"), UUID.randomUUID().toString(), adminPrincipal);
        ledgerService.deposit(bobAccount.getId(), new DepositRequest(50000L, "Seed Bob"), UUID.randomUUID().toString(), adminPrincipal);

        aliceToken = jwtService.generateToken(alice.getId(), alice.getUsername(), alice.getEmail(), alice.getRole());
    }

    @Test
    @DisplayName("Same Idempotency-Key + same payload returns original response with Idempotent-Replay: true")
    void sameKeySamePayloadReturnsReplay() throws Exception {
        String key = UUID.randomUUID().toString();
        DepositRequest req = new DepositRequest(10000L, "First deposit");

        // First call: executes normally
        MvcResult firstResult = mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        String firstTxId = objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("id").asText();

        // Second call: same key, same payload -> must return same txId and header Idempotent-Replay: true
        MvcResult secondResult = mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();

        String secondTxId = objectMapper.readTree(secondResult.getResponse().getContentAsString()).get("id").asText();
        assertThat(firstTxId).isEqualTo(secondTxId);

        // Verify balance was only credited once (+10000)
        Account updated = accountRepository.findById(aliceAccount.getId()).get();
        assertThat(updated.getBalance()).isEqualTo(110000L);
    }

    @Test
    @DisplayName("Same Idempotency-Key + different payload returns 422 IDEMPOTENCY_PAYLOAD_MISMATCH")
    void sameKeyDifferentPayloadReturns422() throws Exception {
        String key = UUID.randomUUID().toString();
        DepositRequest req1 = new DepositRequest(10000L, "First deposit");
        DepositRequest req2 = new DepositRequest(20000L, "Different deposit");

        // First call
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Second call with different payload -> 422
        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    @DisplayName("Concurrency: 20 parallel requests with same key execute exactly once")
    void parallel20RequestsSameKeyExecuteOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        TransferRequest req = new TransferRequest(aliceAccount.getId(), bobAccount.getId(), 5000L, "Rent");
        String payloadJson = objectMapper.writeValueAsString(req);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        List<String> returnedTxIds = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + aliceToken)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payloadJson))
                            .andReturn();

                    if (res.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                        String txId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
                        returnedTxIds.add(txId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // All 20 threads must receive the EXACT same transaction ID
        String firstTxId = returnedTxIds.get(0);
        for (String txId : returnedTxIds) {
            assertThat(txId).isEqualTo(firstTxId);
        }

        // Alice was debited only once (-5000)
        Account aliceRefreshed = accountRepository.findById(aliceAccount.getId()).get();
        Account bobRefreshed = accountRepository.findById(bobAccount.getId()).get();

        assertThat(aliceRefreshed.getBalance()).isEqualTo(95000L);
        assertThat(bobRefreshed.getBalance()).isEqualTo(55000L);
    }

    @Test
    @DisplayName("Invalid non-UUID Idempotency-Key returns 400 INVALID_REQUEST")
    void invalidKeyFormatReturns400() throws Exception {
        DepositRequest req = new DepositRequest(5000L, "Deposit");

        mockMvc.perform(post("/api/accounts/" + aliceAccount.getId() + "/deposit")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", "not-a-valid-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
