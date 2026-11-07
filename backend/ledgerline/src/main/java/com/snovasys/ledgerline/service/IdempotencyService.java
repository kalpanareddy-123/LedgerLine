package com.snovasys.ledgerline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snovasys.ledgerline.domain.IdempotencyRecord;
import com.snovasys.ledgerline.dto.TransactionResponse;
import com.snovasys.ledgerline.exception.IdempotencyConflictException;
import com.snovasys.ledgerline.exception.InvalidRequestException;
import com.snovasys.ledgerline.repository.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int STATUS_PROCESSING = 102;

    private final IdempotencyRepository idempotencyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Single-instance inflight barrier: coordinates concurrent threads on this local node only.
    // The real cross-instance guarantee is the database UNIQUE(user_id, key) constraint with ON CONFLICT.
    private final Map<String, CompletableFuture<TransactionResponse>> inflightRequests = new ConcurrentHashMap<>();

    public IdempotencyService(IdempotencyRepository idempotencyRepository,
                              JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<TransactionResponse> executeIdempotent(
            String idempotencyKeyHeader,
            UUID userId,
            Object requestBody,
            Supplier<TransactionResponse> action) {

        String key = validateAndNormalizeKey(idempotencyKeyHeader);
        String currentHash = computeSha256(requestBody);
        String coordinationKey = userId + ":" + key;

        // 1. Fast check if already completed in database
        Optional<IdempotencyRecord> existingOpt = idempotencyRepository.findByUserIdAndKey(userId, key);
        if (existingOpt.isPresent() && existingOpt.get().getResponseStatus() != STATUS_PROCESSING) {
            return resolveExistingRecord(existingOpt.get(), currentHash);
        }

        // 2. In-memory coordination barrier (single-instance optimization to avoid DB polling)
        CompletableFuture<TransactionResponse> newFuture = new CompletableFuture<>();
        CompletableFuture<TransactionResponse> existingFuture = inflightRequests.putIfAbsent(coordinationKey, newFuture);

        if (existingFuture != null) {
            // Another thread in this process is actively processing this key. Wait for it.
            try {
                TransactionResponse response = existingFuture.get(10, TimeUnit.SECONDS);
                IdempotencyRecord completedRecord = idempotencyRepository.findByUserIdAndKey(userId, key).orElse(null);
                if (completedRecord != null && !completedRecord.getRequestHash().equals(currentHash)) {
                    throw new IdempotencyConflictException("Idempotency key has already been used with a different request payload.");
                }
                return ResponseEntity.ok()
                        .header("Idempotent-Replay", "true")
                        .body(response);
            } catch (Exception e) {
                if (e.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException("Concurrent execution failed for key: " + key, e);
            }
        }

        // 3. Primary executor
        try {
            // Check again in case it completed right as future was placed
            Optional<IdempotencyRecord> checkAgain = idempotencyRepository.findByUserIdAndKey(userId, key);
            if (checkAgain.isPresent() && checkAgain.get().getResponseStatus() != STATUS_PROCESSING) {
                ResponseEntity<TransactionResponse> resolved = resolveExistingRecord(checkAgain.get(), currentHash);
                newFuture.complete(resolved.getBody());
                return resolved;
            }

            // Cross-instance guarantee: PostgreSQL UNIQUE(user_id, key) with ON CONFLICT DO NOTHING
            String insertSql = """
                    INSERT INTO idempotency_keys (id, user_id, key, request_hash, response_status, response_body, created_at)
                    VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?, 'PROCESSING', CURRENT_TIMESTAMP)
                    ON CONFLICT (user_id, key) DO NOTHING
                    RETURNING id
                    """;
            List<UUID> claimed = jdbcTemplate.query(
                    insertSql,
                    (rs, rowNum) -> (UUID) rs.getObject("id"),
                    userId.toString(), key, currentHash, STATUS_PROCESSING
            );

            if (claimed.isEmpty()) {
                // Claimed by another thread or node: await DB completion
                ResponseEntity<TransactionResponse> replayed = awaitAndReplay(userId, key, currentHash);
                newFuture.complete(replayed.getBody());
                return replayed;
            }

            // Perform money movement
            UUID recordId = claimed.get(0);
            TransactionResponse response;
            try {
                response = action.get();
            } catch (RuntimeException ex) {
                // Key Release Policy: If initial attempt fails (insufficient funds, validation, etc.),
                // the key is released so the user can safely retry after depositing funds or correcting input.
                jdbcTemplate.update("DELETE FROM idempotency_keys WHERE id = ?::uuid", recordId);
                newFuture.completeExceptionally(ex);
                throw ex;
            }

            // Persist completed record
            String responseJson = objectMapper.writeValueAsString(response);
            jdbcTemplate.update(
                    "UPDATE idempotency_keys SET response_status = 200, response_body = ?, transaction_id = ?::uuid WHERE id = ?::uuid",
                    responseJson, response.id().toString(), recordId.toString()
            );

            newFuture.complete(response);
            return ResponseEntity.ok(response);

        } catch (RuntimeException ex) {
            newFuture.completeExceptionally(ex);
            throw ex;
        } catch (Exception ex) {
            newFuture.completeExceptionally(ex);
            throw new RuntimeException("Unexpected error during idempotent execution", ex);
        } finally {
            inflightRequests.remove(coordinationKey);
        }
    }

    private ResponseEntity<TransactionResponse> awaitAndReplay(UUID userId, String key, String currentHash) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = 10000;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            IdempotencyRecord record = idempotencyRepository.findByUserIdAndKey(userId, key).orElse(null);

            if (record != null) {
                if (!record.getRequestHash().equals(currentHash)) {
                    throw new IdempotencyConflictException("Idempotency key has already been used with a different request payload.");
                }

                if (record.getResponseStatus() != STATUS_PROCESSING) {
                    try {
                        TransactionResponse cachedResponse = objectMapper.readValue(
                                record.getResponseBody(),
                                TransactionResponse.class
                        );
                        return ResponseEntity
                                .status(record.getResponseStatus())
                                .header("Idempotent-Replay", "true")
                                .body(cachedResponse);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to deserialize cached idempotent response", e);
                    }
                }
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for concurrent idempotent execution", e);
            }
        }

        throw new RuntimeException("Timeout waiting for concurrent idempotent execution on key: " + key);
    }

    private ResponseEntity<TransactionResponse> resolveExistingRecord(IdempotencyRecord record, String currentHash) {
        if (!record.getRequestHash().equals(currentHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key has already been used with a different request payload."
            );
        }

        try {
            TransactionResponse cachedResponse = objectMapper.readValue(
                    record.getResponseBody(),
                    TransactionResponse.class
            );
            return ResponseEntity
                    .status(record.getResponseStatus())
                    .header("Idempotent-Replay", "true")
                    .body(cachedResponse);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached idempotent response", e);
        }
    }

    private String validateAndNormalizeKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new InvalidRequestException("Idempotency-Key header is required");
        }
        String trimmed = key.trim();
        try {
            UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Idempotency-Key header must be a valid UUID");
        }
        return trimmed;
    }

    public String computeSha256(Object object) {
        try {
            String json = objectMapper.writeValueAsString(object);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request payload for hashing", e);
        }
    }
}
