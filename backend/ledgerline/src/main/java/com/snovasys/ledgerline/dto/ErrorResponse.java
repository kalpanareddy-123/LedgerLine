package com.snovasys.ledgerline.dto;

import java.time.Instant;

public record ErrorResponse(
        String timestamp,
        int status,
        String code,
        String message,
        String path,
        String requestId
) {
    public static ErrorResponse of(int status, String code, String message, String path, String requestId) {
        return new ErrorResponse(Instant.now().toString(), status, code, message, path, requestId);
    }
}
