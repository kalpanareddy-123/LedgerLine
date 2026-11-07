package com.snovasys.ledgerline.dto;

import com.snovasys.ledgerline.domain.Role;
import java.util.UUID;

public record AuthResponse(
        String token,
        String type,
        UUID userId,
        String username,
        String email,
        Role role
) {
    public static AuthResponse of(String token, UUID userId, String username, String email, Role role) {
        return new AuthResponse(token, "Bearer", userId, username, email, role);
    }
}
