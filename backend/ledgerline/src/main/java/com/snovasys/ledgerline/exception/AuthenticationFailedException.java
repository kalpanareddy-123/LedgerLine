package com.snovasys.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class AuthenticationFailedException extends ApiException {
    public AuthenticationFailedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", message);
    }
}
