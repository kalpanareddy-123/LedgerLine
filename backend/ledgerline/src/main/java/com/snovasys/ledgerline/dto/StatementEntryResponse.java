package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.domain.Direction;
import com.snovasys.ledgerline.domain.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

public record StatementEntryResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        Direction direction,
        Long amount,
        Long balanceAfter,
        Instant createdAt
) {
    public static StatementEntryResponse fromEntity(LedgerEntry entry) {
        return new StatementEntryResponse(
                entry.getId(),
                entry.getTransactionId(),
                entry.getAccountId(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }
}
