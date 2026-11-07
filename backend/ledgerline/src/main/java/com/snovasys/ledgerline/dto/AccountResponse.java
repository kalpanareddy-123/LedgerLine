package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID ownerId,
        String accountNumber,
        String currency,
        Long balance,
        AccountStatus status,
        boolean isSystem,
        Instant createdAt
) {
    public static AccountResponse fromEntity(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.isSystem(),
                account.getCreatedAt()
        );
    }
}
