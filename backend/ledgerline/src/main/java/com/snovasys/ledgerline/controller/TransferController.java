package com.snovasys.ledgerline.controller;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransactionResponse;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawRequest;
import com.snovasys.ledgerline.security.UserPrincipal;
import com.snovasys.ledgerline.service.IdempotencyService;
import com.snovasys.ledgerline.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Money Movement & Transfers", description = "Idempotent deposits, withdrawals, and double-entry transfers")
@RestController
@RequestMapping("/api")
public class TransferController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    public TransferController(LedgerService ledgerService, IdempotencyService idempotencyService) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
    }

    @Operation(summary = "Deposit funds into an account", description = "Credits the destination account and debits the system clearing account. Requires Idempotency-Key header.")
    @PostMapping("/accounts/{id}/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID id,
            @Parameter(description = "Unique UUIDv4 idempotency key to prevent double-execution on network retries", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        return idempotencyService.executeIdempotent(
                idempotencyKey,
                currentUser.getId(),
                request,
                () -> ledgerService.deposit(id, request, idempotencyKey, currentUser)
        );
    }

    @Operation(summary = "Withdraw funds from an account", description = "Debits the source account and credits the system clearing account. Verifies sufficient funds under lock. Requires Idempotency-Key header.")
    @PostMapping("/accounts/{id}/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID id,
            @Parameter(description = "Unique UUIDv4 idempotency key to prevent double-execution on network retries", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        return idempotencyService.executeIdempotent(
                idempotencyKey,
                currentUser.getId(),
                request,
                () -> ledgerService.withdraw(id, request, idempotencyKey, currentUser)
        );
    }

    @Operation(summary = "Transfer funds between two accounts", description = "Executes an atomic double-entry transfer with deterministic ascending row locks. Requires Idempotency-Key header.")
    @PostMapping("/transfers")
    public ResponseEntity<TransactionResponse> transfer(
            @Parameter(description = "Unique UUIDv4 idempotency key to prevent double-execution on network retries", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        return idempotencyService.executeIdempotent(
                idempotencyKey,
                currentUser.getId(),
                request,
                () -> ledgerService.transfer(request, idempotencyKey, currentUser)
        );
    }
}
