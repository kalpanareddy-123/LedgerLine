package com.snovasys.ledgerline.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DepositRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Deposit amount must be strictly greater than zero")
        Long amount,

        String reference
) {}
