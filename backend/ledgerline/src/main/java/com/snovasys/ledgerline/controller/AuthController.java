package com.snovasys.ledgerline.controller;

import com.snovasys.ledgerline.dto.AuthResponse;
import com.snovasys.ledgerline.dto.LoginRequest;
import com.snovasys.ledgerline.dto.UserResponse;
import com.snovasys.ledgerline.service.AuditService;
import com.snovasys.ledgerline.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    public AuthController(AuthService authService, AuditService auditService) {
        this.authService = authService;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = auditService.getClientIp();
        String requestId = auditService.getCurrentRequestId();
        AuthResponse response = authService.login(request, ip, requestId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {
        return ResponseEntity.ok(authService.getCurrentUser());
    }
}
