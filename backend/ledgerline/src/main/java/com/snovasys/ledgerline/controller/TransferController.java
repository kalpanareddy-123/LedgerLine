package com.snovasys.ledgerline.controller;

import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransactionResponse;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawRequest;
import com.snovasys.ledgerline.security.UserPrincipal;
import com.snovasys.ledgerline.service.IdempotencyService;
import com.snovasys.ledgerline.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;

    public TransferController(LedgerService ledgerService, IdempotencyService idempotencyService) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/accounts/{id}/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID id,
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

    @PostMapping("/accounts/{id}/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID id,
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

    @PostMapping("/transfers")
    public ResponseEntity<TransactionResponse> transfer(
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
