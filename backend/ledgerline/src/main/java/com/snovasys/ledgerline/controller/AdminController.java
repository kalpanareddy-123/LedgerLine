package com.snovasys.ledgerline.controller;

import com.snovasys.ledgerline.dto.*;
import com.snovasys.ledgerline.service.AccountService;
import com.snovasys.ledgerline.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final AccountService accountService;
    private final com.snovasys.ledgerline.service.ReconciliationService reconciliationService;

    public AdminController(AdminService adminService,
                           AccountService accountService,
                           com.snovasys.ledgerline.service.ReconciliationService reconciliationService) {
        this.adminService = adminService;
        this.accountService = accountService;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = adminService.createUser(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PatchMapping("/accounts/{id}/status")
    public ResponseEntity<AccountResponse> updateAccountStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        AccountResponse response = accountService.updateAccountStatus(id, request.status());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationResponse> getReconciliation() {
        return ResponseEntity.ok(reconciliationService.runReconciliation());
    }
}
