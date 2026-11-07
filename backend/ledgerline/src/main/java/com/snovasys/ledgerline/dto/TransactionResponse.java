package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.domain.Transaction;
import com.snovasys.ledgerline.domain.TransactionStatus;
import com.snovasys.ledgerline.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        TransactionType type,
        TransactionStatus status,
        Long amount,
        String currency,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String reference,
        Instant createdAt
) {
    public static TransactionResponse fromEntity(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getSourceAccountId(),
                tx.getDestinationAccountId(),
                tx.getReference(),
                tx.getCreatedAt()
        );
    }
}
