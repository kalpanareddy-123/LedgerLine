package com.snovasys.ledgerline.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationResponse(
        String status,
        boolean isBalanced,
        Long totalLedgerSum,
        int accountsAudited,
        List<AccountMismatch> mismatches,
        Instant timestamp
) {
    public record AccountMismatch(
            UUID accountId,
            String accountNumber,
            Long storedBalance,
            Long calculatedBalance,
            Long discrepancy
    ) {}
}
