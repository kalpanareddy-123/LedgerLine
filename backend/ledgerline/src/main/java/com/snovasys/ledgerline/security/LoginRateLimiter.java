package com.snovasys.ledgerline.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_DURATION_SECONDS = 300; // 5 minutes lockout
    private static final long ATTEMPT_WINDOW_SECONDS = 600; // 10 minutes sliding window

    private static class Attempt {
        final int count;
        final Instant firstAttemptAt;
        final Instant lockUntil;

        Attempt(int count, Instant firstAttemptAt, Instant lockUntil) {
            this.count = count;
            this.firstAttemptAt = firstAttemptAt;
            this.lockUntil = lockUntil;
        }
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        Instant now = Instant.now();
        if (attempt.lockUntil != null) {
            if (now.isBefore(attempt.lockUntil)) {
                return true;
            } else {
                // Lock expired - clear record so client can attempt again
                attempts.remove(key);
                return false;
            }
        }
        // If window expired without lockout, remove stale attempt
        if (now.isAfter(attempt.firstAttemptAt.plusSeconds(ATTEMPT_WINDOW_SECONDS))) {
            attempts.remove(key);
            return false;
        }
        return false;
    }

    public void recordFailure(String key) {
        Instant now = Instant.now();
        attempts.compute(key, (k, current) -> {
            if (current == null || now.isAfter(current.firstAttemptAt.plusSeconds(ATTEMPT_WINDOW_SECONDS))) {
                return new Attempt(1, now, null);
            }
            int newCount = current.count + 1;
            Instant lockUntil = null;
            if (newCount >= MAX_ATTEMPTS) {
                lockUntil = now.plusSeconds(LOCK_DURATION_SECONDS);
            }
            return new Attempt(newCount, current.firstAttemptAt, lockUntil);
        });
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }
}
