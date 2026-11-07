package com.snovasys.ledgerline.controller;

import com.snovasys.ledgerline.dto.AccountResponse;
import com.snovasys.ledgerline.security.UserPrincipal;
import com.snovasys.ledgerline.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts(@AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(accountService.getAccounts(currentUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccountById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(accountService.getAccountById(id, currentUser));
    }
}
