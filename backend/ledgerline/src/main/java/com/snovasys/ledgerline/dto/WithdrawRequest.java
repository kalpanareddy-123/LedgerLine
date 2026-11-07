package com.snovasys.ledgerline.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WithdrawRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Withdrawal amount must be strictly greater than zero")
        Long amount,

        String reference
) {}
