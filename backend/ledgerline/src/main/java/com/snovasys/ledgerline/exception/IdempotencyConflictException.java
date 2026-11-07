package com.snovasys.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends ApiException {
    public IdempotencyConflictException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_PAYLOAD_MISMATCH", message);
    }
}
