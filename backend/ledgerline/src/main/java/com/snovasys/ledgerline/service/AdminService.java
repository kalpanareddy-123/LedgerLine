package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.AuditOutcome;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.CreateUserRequest;
import com.snovasys.ledgerline.dto.UserResponse;
import com.snovasys.ledgerline.exception.InvalidRequestException;
import com.snovasys.ledgerline.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminService(UserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username().trim())) {
            throw new InvalidRequestException("Username is already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email().trim())) {
            throw new InvalidRequestException("Email is already in use: " + request.email());
        }

        User user = new User(
                request.username().trim(),
                request.email().trim().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.role()
        );

        User saved = userRepository.save(user);

        auditService.recordAction(
                "CREATE_USER",
                "USER",
                saved.getId().toString(),
                AuditOutcome.SUCCESS,
                "{\"username\":\"" + saved.getUsername() + "\",\"role\":\"" + saved.getRole() + "\"}"
        );

        return UserResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(UserResponse::fromEntity)
                .toList();
    }
}
