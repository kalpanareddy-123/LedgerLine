package com.snovasys.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class AccountClosedException extends ApiException {
    public AccountClosedException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_CLOSED", message);
    }
}
