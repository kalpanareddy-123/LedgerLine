package com.snovasys.ledgerline.dto;

import org.springframework.data.domain.Page;
import java.util.UUID;

public record StatementResponse(
        UUID accountId,
        String accountNumber,
        String currency,
        Long currentBalance,
        Page<StatementEntryResponse> entries
) {}
