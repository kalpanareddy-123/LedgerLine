package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.AccountStatus;
import com.snovasys.ledgerline.domain.AuditOutcome;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.dto.AccountResponse;
import com.snovasys.ledgerline.dto.CreateAccountRequest;
import com.snovasys.ledgerline.exception.InvalidRequestException;
import com.snovasys.ledgerline.exception.ResourceNotFoundException;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          AuditService auditService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccounts(UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ACCOUNT_HOLDER) {
            return accountRepository.findByOwnerId(currentUser.getId())
                    .stream()
                    .map(AccountResponse::fromEntity)
                    .toList();
        }
        // ADMIN and AUDITOR see all user accounts
        return accountRepository.findAllUserAccounts()
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID accountId, UserPrincipal currentUser) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + accountId));

        // Ownership enforcement: Account holder must get 404 (not 403) so account existence does not leak
        if (currentUser.getRole() == Role.ACCOUNT_HOLDER) {
            if (account.getOwnerId() == null || !account.getOwnerId().equals(currentUser.getId())) {
                throw new ResourceNotFoundException("Account not found with ID: " + accountId);
            }
        }

        return AccountResponse.fromEntity(account);
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (!userRepository.existsById(request.ownerId())) {
            throw new ResourceNotFoundException("Account owner not found with ID: " + request.ownerId());
        }

        String accountNumber = request.accountNumber();
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            accountNumber = generateUniqueAccountNumber();
        } else {
            if (accountRepository.existsByAccountNumber(accountNumber.trim())) {
                throw new InvalidRequestException("Account number already exists: " + accountNumber);
            }
        }

        Account account = new Account(request.ownerId(), accountNumber.trim(), 0L, false);
        Account saved = accountRepository.save(account);

        auditService.recordAction(
                "CREATE_ACCOUNT",
                "ACCOUNT",
                saved.getId().toString(),
                AuditOutcome.SUCCESS,
                "{\"accountNumber\":\"" + saved.getAccountNumber() + "\",\"ownerId\":\"" + saved.getOwnerId() + "\"}"
        );

        return AccountResponse.fromEntity(saved);
    }

    @Transactional
    public AccountResponse updateAccountStatus(UUID accountId, AccountStatus newStatus) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + accountId));

        if (account.isSystem()) {
            throw new InvalidRequestException("System clearing accounts cannot be modified");
        }

        AccountStatus oldStatus = account.getStatus();
        account.setStatus(newStatus);
        Account saved = accountRepository.save(account);

        auditService.recordAction(
                "UPDATE_ACCOUNT_STATUS",
                "ACCOUNT",
                saved.getId().toString(),
                AuditOutcome.SUCCESS,
                "{\"oldStatus\":\"" + oldStatus + "\",\"newStatus\":\"" + newStatus + "\"}"
        );

        return AccountResponse.fromEntity(saved);
    }

    private String generateUniqueAccountNumber() {
        String num;
        do {
            long randNum = 1000000000L + (long) (random.nextDouble() * 9000000000L);
            num = "ACC-" + randNum;
        } while (accountRepository.existsByAccountNumber(num));
        return num;
    }
}
