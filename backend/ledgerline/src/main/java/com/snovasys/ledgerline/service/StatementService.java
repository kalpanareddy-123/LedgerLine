package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.LedgerEntry;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.dto.StatementEntryResponse;
import com.snovasys.ledgerline.dto.StatementResponse;
import com.snovasys.ledgerline.exception.ResourceNotFoundException;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.LedgerEntryRepository;
import com.snovasys.ledgerline.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class StatementService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public StatementService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public StatementResponse getStatement(UUID accountId, Instant from, Instant to, Pageable pageable, UserPrincipal currentUser) {
        Account account = getAccountWithOwnershipCheck(accountId, currentUser);

        Page<LedgerEntry> entriesPage;
        if (from != null && to != null) {
            entriesPage = ledgerEntryRepository.findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                    accountId, from, to, pageable);
        } else {
            entriesPage = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
        }

        Page<StatementEntryResponse> mappedPage = entriesPage.map(StatementEntryResponse::fromEntity);

        return new StatementResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance(),
                mappedPage
        );
    }

    @Transactional(readOnly = true)
    public String exportStatementCsv(UUID accountId, Instant from, Instant to, UserPrincipal currentUser) {
        Account account = getAccountWithOwnershipCheck(accountId, currentUser);

        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtAsc(accountId);

        StringBuilder csv = new StringBuilder();
        csv.append("Date,Transaction ID,Direction,Amount (Paise),Amount (INR),Balance After (Paise),Balance After (INR)\n");

        for (LedgerEntry entry : entries) {
            if (from != null && entry.getCreatedAt().isBefore(from)) continue;
            if (to != null && entry.getCreatedAt().isAfter(to)) continue;

            String amountInr = formatPaiseToRupees(entry.getAmount());
            String balanceAfterInr = formatPaiseToRupees(entry.getBalanceAfter());

            csv.append(entry.getCreatedAt()).append(",")
                    .append(entry.getTransactionId()).append(",")
                    .append(entry.getDirection()).append(",")
                    .append(entry.getAmount()).append(",")
                    .append(amountInr).append(",")
                    .append(entry.getBalanceAfter()).append(",")
                    .append(balanceAfterInr).append("\n");
        }

        return csv.toString();
    }

    private Account getAccountWithOwnershipCheck(UUID accountId, UserPrincipal currentUser) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + accountId));

        if (currentUser.getRole() == Role.ACCOUNT_HOLDER) {
            if (account.getOwnerId() == null || !account.getOwnerId().equals(currentUser.getId())) {
                // Return 404 (not 403) to prevent account existence probing
                throw new ResourceNotFoundException("Account not found with ID: " + accountId);
            }
        }
        return account;
    }

    private String formatPaiseToRupees(Long paise) {
        if (paise == null) return "0.00";
        BigDecimal rupees = BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);
        return rupees.toPlainString();
    }
}
