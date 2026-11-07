package com.snovasys.ledgerline.exception;

import org.springframework.http.HttpStatus;

public class AccountFrozenException extends ApiException {
    public AccountFrozenException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_FROZEN", message);
    }
}
