package com.snovasys.ledgerline.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateAccountRequest(
        @NotNull(message = "Owner ID is required")
        UUID ownerId,

        String accountNumber,

        Long initialBalance
) {}
