package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
        @NotNull(message = "Status is required (ACTIVE, FROZEN, CLOSED)")
        AccountStatus status
) {}
