package com.snovasys.ledgerline.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_DURATION_SECONDS = 300; // 5 minutes

    private static class Attempt {
        int count;
        Instant lockUntil;

        Attempt(int count, Instant lockUntil) {
            this.count = count;
            this.lockUntil = lockUntil;
        }
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockUntil != null) {
            if (Instant.now().isBefore(attempt.lockUntil)) {
                return true;
            } else {
                // Lock expired
                attempts.remove(key);
                return false;
            }
        }
        return false;
    }

    public void recordFailure(String key) {
        attempts.compute(key, (k, current) -> {
            if (current == null) {
                return new Attempt(1, null);
            }
            int newCount = current.count + 1;
            Instant lockUntil = null;
            if (newCount >= MAX_ATTEMPTS) {
                lockUntil = Instant.now().plusSeconds(LOCK_DURATION_SECONDS);
            }
            return new Attempt(newCount, lockUntil);
        });
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }
}
