package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.AuditOutcome;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.AuthResponse;
import com.snovasys.ledgerline.dto.LoginRequest;
import com.snovasys.ledgerline.dto.UserResponse;
import com.snovasys.ledgerline.exception.AuthenticationFailedException;
import com.snovasys.ledgerline.exception.RateLimitException;
import com.snovasys.ledgerline.exception.ResourceNotFoundException;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.security.JwtService;
import com.snovasys.ledgerline.security.LoginRateLimiter;
import com.snovasys.ledgerline.security.UserPrincipal;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final LoginRateLimiter rateLimiter;
    private final AuditService auditService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       UserRepository userRepository,
                       LoginRateLimiter rateLimiter,
                       AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    public AuthResponse login(LoginRequest request, String ip, String requestId) {
        String rateLimitKey = request.username().trim().toLowerCase();

        if (rateLimiter.isLocked(rateLimitKey)) {
            auditService.record(null, "LOGIN_ATTEMPT", "USER", rateLimitKey,
                    AuditOutcome.DENIED, ip, requestId, "{\"reason\":\"Brute force rate limit lockout\"}");
            throw new RateLimitException("Too many failed login attempts. Please try again in 5 minutes.");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            rateLimiter.recordSuccess(rateLimitKey);

            String token = jwtService.generateToken(
                    principal.getId(),
                    principal.getUsername(),
                    principal.getEmail(),
                    principal.getRole()
            );

            auditService.record(principal.getId(), "LOGIN", "USER", principal.getId().toString(),
                    AuditOutcome.SUCCESS, ip, requestId, "{\"username\":\"" + principal.getUsername() + "\"}");

            return AuthResponse.of(token, principal.getId(), principal.getUsername(), principal.getEmail(), principal.getRole());
        } catch (BadCredentialsException ex) {
            rateLimiter.recordFailure(rateLimitKey);
            auditService.record(null, "LOGIN_FAILED", "USER", rateLimitKey,
                    AuditOutcome.FAILURE, ip, requestId, "{\"reason\":\"Bad credentials\"}");
            throw ex;
        }
    }

    public UserResponse getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new AuthenticationFailedException("User is not authenticated");
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return UserResponse.fromEntity(user);
    }
}
