package com.snovasys.ledgerline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SchemaAndHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("GET /api/health should return 200 UP")
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("LedgerLine Core Engine"));
    }

    @Test
    @DisplayName("Flyway V1 schema should have seeded CASH_CLEARING system account")
    void systemAccountIsSeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE account_number = 'SYS_CASH_CLEARING' AND is_system = true",
                Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("DB CHECK constraint: non-system account cannot have negative balance")
    void nonSystemAccountCannotHaveNegativeBalance() {
        assertThatThrownBy(() -> {
            jdbcTemplate.update(
                    "INSERT INTO accounts (account_number, currency, balance, status, is_system) VALUES (?, ?, ?, ?, ?)",
                    "TEST_ACC_NEG", "INR", -500L, "ACTIVE", false
            );
        }).hasMessageContaining("chk_account_balance");
    }

    @Test
    @DisplayName("DB Trigger: ledger_entries is strictly append-only (UPDATE and DELETE fail)")
    void ledgerEntriesAppendOnlyTriggerFailsOnUpdateOrDelete() {
        // Create user, accounts, and transaction to test ledger_entry triggers
        String userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (username, email, password_hash, role) VALUES ('trg_user', 'trg@test.com', 'hash', 'ACCOUNT_HOLDER') RETURNING id",
                String.class
        );
        String accId = jdbcTemplate.queryForObject(
                "INSERT INTO accounts (owner_id, account_number, currency, balance, status, is_system) VALUES (?::uuid, 'TRG_ACC', 'INR', 1000, 'ACTIVE', false) RETURNING id",
                String.class, userId
        );
        String txId = jdbcTemplate.queryForObject(
                "INSERT INTO transactions (type, status, amount, currency, destination_account_id) VALUES ('DEPOSIT', 'COMPLETED', 1000, 'INR', ?::uuid) RETURNING id",
                String.class, accId
        );
        String entryId = jdbcTemplate.queryForObject(
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount, balance_after) VALUES (?::uuid, ?::uuid, 'CREDIT', 1000, 1000) RETURNING id",
                String.class, txId, accId
        );

        // Attempt UPDATE -> must throw trigger exception
        assertThatThrownBy(() -> {
            jdbcTemplate.update("UPDATE ledger_entries SET amount = 2000 WHERE id = ?::uuid", entryId);
        }).hasMessageContaining("append-only");

        // Attempt DELETE -> must throw trigger exception
        assertThatThrownBy(() -> {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?::uuid", entryId);
        }).hasMessageContaining("append-only");
    }
}
