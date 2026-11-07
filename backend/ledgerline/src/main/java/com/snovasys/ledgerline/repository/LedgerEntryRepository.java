package com.snovasys.ledgerline.repository;

import com.snovasys.ledgerline.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId, Pageable pageable);

    Page<LedgerEntry> findByAccountIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            UUID accountId, Instant from, Instant to, Pageable pageable);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    /**
     * Net ledger sum across all accounts.
     * Credit is positive inflow, Debit is negative outflow.
     * In a balanced double-entry system, this MUST equal 0.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.direction = com.snovasys.ledgerline.domain.Direction.CREDIT THEN e.amount ELSE -e.amount END), 0) FROM LedgerEntry e")
    Long calculateTotalLedgerSum();

    /**
     * Net sum of ledger entries for a specific account.
     * Must equal the account's current balance.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.direction = com.snovasys.ledgerline.domain.Direction.CREDIT THEN e.amount ELSE -e.amount END), 0) FROM LedgerEntry e WHERE e.accountId = :accountId")
    Long calculateAccountLedgerSum(@Param("accountId") UUID accountId);
}
