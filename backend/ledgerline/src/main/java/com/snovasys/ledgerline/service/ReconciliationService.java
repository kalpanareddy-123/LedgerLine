package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.dto.ReconciliationResponse;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconciliationService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse runReconciliation() {
        Long totalLedgerSum = ledgerEntryRepository.calculateTotalLedgerSum();
        List<Account> allAccounts = accountRepository.findAll();

        List<ReconciliationResponse.AccountMismatch> mismatches = new ArrayList<>();

        for (Account account : allAccounts) {
            Long storedBalance = account.getBalance();
            Long calculatedBalance = ledgerEntryRepository.calculateAccountLedgerSum(account.getId());

            if (!storedBalance.equals(calculatedBalance)) {
                mismatches.add(new ReconciliationResponse.AccountMismatch(
                        account.getId(),
                        account.getAccountNumber(),
                        storedBalance,
                        calculatedBalance,
                        storedBalance - calculatedBalance
                ));
            }
        }

        boolean isBalanced = (totalLedgerSum == 0L) && mismatches.isEmpty();
        String status = isBalanced ? "OK" : "FAILED";

        return new ReconciliationResponse(
                status,
                isBalanced,
                totalLedgerSum,
                allAccounts.size(),
                mismatches,
                Instant.now()
        );
    }
}
