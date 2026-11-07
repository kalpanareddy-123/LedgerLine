package com.snovasys.ledgerline.service;

import com.snovasys.ledgerline.domain.*;
import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.dto.TransactionResponse;
import com.snovasys.ledgerline.dto.TransferRequest;
import com.snovasys.ledgerline.dto.WithdrawRequest;
import com.snovasys.ledgerline.exception.*;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.LedgerEntryRepository;
import com.snovasys.ledgerline.repository.TransactionRepository;
import com.snovasys.ledgerline.security.UserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class LedgerService {

    public static final UUID CASH_CLEARING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final long MAX_TRANSACTION_AMOUNT = 10_000_000_00L; // 1 Crore INR (in paise)

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;

    public LedgerService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         LedgerEntryRepository ledgerEntryRepository,
                         AuditService auditService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
    }

    /**
     * Deposit money into a user account with CASH_CLEARING counterparty.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse deposit(UUID targetAccountId, DepositRequest request, String idempotencyKey, UserPrincipal currentUser) {
        validateRoleCanMoveMoney(currentUser);
        validateAmount(request.amount());

        // Lock both CASH_CLEARING and target account in strict ascending ID order
        AccountPair accounts = lockAccountsInOrder(CASH_CLEARING_ACCOUNT_ID, targetAccountId);
        Account systemAcc = accounts.first();
        Account targetAcc = accounts.second();

        // Ownership enforcement: Account holder must own the target account (404 on mismatch)
        validateOwnership(targetAcc, currentUser);

        // Account status checks
        validateAccountActive(targetAcc);

        long amount = request.amount();

        // 1. Update balances
        // System clearing account decreases by amount (allowed to be negative)
        systemAcc.setBalance(systemAcc.getBalance() - amount);
        accountRepository.save(systemAcc);

        // Target user account increases by amount
        targetAcc.setBalance(targetAcc.getBalance() + amount);
        accountRepository.save(targetAcc);

        // 2. Record Transaction
        Transaction tx = new Transaction(
                TransactionType.DEPOSIT,
                TransactionStatus.COMPLETED,
                amount,
                systemAcc.getId(),
                targetAcc.getId(),
                request.reference(),
                idempotencyKey,
                currentUser.getId()
        );
        Transaction savedTx = transactionRepository.save(tx);

        // 3. Double-entry ledger entries: sum must equal zero
        // DEBIT on system account (-amount)
        LedgerEntry entrySystem = new LedgerEntry(
                savedTx.getId(),
                systemAcc.getId(),
                Direction.DEBIT,
                amount,
                systemAcc.getBalance()
        );
        ledgerEntryRepository.save(entrySystem);

        // CREDIT on user account (+amount)
        LedgerEntry entryTarget = new LedgerEntry(
                savedTx.getId(),
                targetAcc.getId(),
                Direction.CREDIT,
                amount,
                targetAcc.getBalance()
        );
        ledgerEntryRepository.save(entryTarget);

        auditService.recordAction(
                "DEPOSIT",
                "ACCOUNT",
                targetAcc.getId().toString(),
                AuditOutcome.SUCCESS,
                "{\"amount\":" + amount + ",\"txId\":\"" + savedTx.getId() + "\"}"
        );

        return TransactionResponse.fromEntity(savedTx);
    }

    /**
     * Withdraw money from a user account with CASH_CLEARING counterparty.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse withdraw(UUID sourceAccountId, WithdrawRequest request, String idempotencyKey, UserPrincipal currentUser) {
        validateRoleCanMoveMoney(currentUser);
        validateAmount(request.amount());

        // Lock both source account and CASH_CLEARING in strict ascending ID order
        AccountPair accounts = lockAccountsInOrder(sourceAccountId, CASH_CLEARING_ACCOUNT_ID);
        Account sourceAcc = accounts.first();
        Account systemAcc = accounts.second();

        // Ownership enforcement: Account holder must own the source account (404 on mismatch)
        validateOwnership(sourceAcc, currentUser);

        // Account status checks
        validateAccountActive(sourceAcc);

        long amount = request.amount();

        // Balance check inside transaction after acquiring pessimistic lock (Layer 1)
        if (sourceAcc.getBalance() < amount) {
            throw new InsufficientFundsException(
                    "Insufficient funds: account balance is " + sourceAcc.getBalance() + " paise, requested " + amount + " paise."
            );
        }

        // 1. Update balances
        sourceAcc.setBalance(sourceAcc.getBalance() - amount);
        accountRepository.save(sourceAcc);

        systemAcc.setBalance(systemAcc.getBalance() + amount);
        accountRepository.save(systemAcc);

        // 2. Record Transaction
        Transaction tx = new Transaction(
                TransactionType.WITHDRAWAL,
                TransactionStatus.COMPLETED,
                amount,
                sourceAcc.getId(),
                systemAcc.getId(),
                request.reference(),
                idempotencyKey,
                currentUser.getId()
        );
        Transaction savedTx = transactionRepository.save(tx);

        // 3. Double-entry ledger entries: sum must equal zero
        // DEBIT on user account (-amount)
        LedgerEntry entrySource = new LedgerEntry(
                savedTx.getId(),
                sourceAcc.getId(),
                Direction.DEBIT,
                amount,
                sourceAcc.getBalance()
        );
        ledgerEntryRepository.save(entrySource);

        // CREDIT on system clearing account (+amount)
        LedgerEntry entrySystem = new LedgerEntry(
                savedTx.getId(),
                systemAcc.getId(),
                Direction.CREDIT,
                amount,
                systemAcc.getBalance()
        );
        ledgerEntryRepository.save(entrySystem);

        auditService.recordAction(
                "WITHDRAW",
                "ACCOUNT",
                sourceAcc.getId().toString(),
                AuditOutcome.SUCCESS,
                "{\"amount\":" + amount + ",\"txId\":\"" + savedTx.getId() + "\"}"
        );

        return TransactionResponse.fromEntity(savedTx);
    }

    /**
     * Peer-to-peer Transfer between two distinct accounts.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse transfer(TransferRequest request, String idempotencyKey, UserPrincipal currentUser) {
        validateRoleCanMoveMoney(currentUser);
        validateAmount(request.amount());

        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new InvalidRequestException("Self-transfer is prohibited: source and destination must differ");
        }

        // Lock accounts in strict ascending UUID order to guarantee absolute deadlock freedom
        AccountPair accounts = lockAccountsInOrder(request.sourceAccountId(), request.destinationAccountId());
        Account sourceAcc = accounts.first();
        Account destAcc = accounts.second();

        // Ownership enforcement: Account holder must own the source account (404 on mismatch)
        validateOwnership(sourceAcc, currentUser);

        // Account status checks
        validateAccountActive(sourceAcc);
        validateAccountActive(destAcc);

        long amount = request.amount();

        // Balance check inside transaction after acquiring pessimistic lock (Layer 1)
        if (sourceAcc.getBalance() < amount) {
            throw new InsufficientFundsException(
                    "Insufficient funds: account balance is " + sourceAcc.getBalance() + " paise, requested " + amount + " paise."
            );
        }

        // 1. Update balances
        sourceAcc.setBalance(sourceAcc.getBalance() - amount);
        accountRepository.save(sourceAcc);

        destAcc.setBalance(destAcc.getBalance() + amount);
        accountRepository.save(destAcc);

        // 2. Record Transaction
        Transaction tx = new Transaction(
                TransactionType.TRANSFER,
                TransactionStatus.COMPLETED,
                amount,
                sourceAcc.getId(),
                destAcc.getId(),
                request.reference(),
                idempotencyKey,
                currentUser.getId()
        );
        Transaction savedTx = transactionRepository.save(tx);

        // 3. Double-entry ledger entries: sum must equal zero
        // DEBIT on source account (-amount)
        LedgerEntry entrySource = new LedgerEntry(
                savedTx.getId(),
                sourceAcc.getId(),
                Direction.DEBIT,
                amount,
                sourceAcc.getBalance()
        );
        ledgerEntryRepository.save(entrySource);

        // CREDIT on destination account (+amount)
        LedgerEntry entryDest = new LedgerEntry(
                savedTx.getId(),
                destAcc.getId(),
                Direction.CREDIT,
                amount,
                destAcc.getBalance()
        );
        ledgerEntryRepository.save(entryDest);

        auditService.recordAction(
                "TRANSFER",
                "ACCOUNT",
                sourceAcc.getId().toString(),
                AuditOutcome.SUCCESS,
                "{\"amount\":" + amount + ",\"destAccount\":\"" + destAcc.getId() + "\",\"txId\":\"" + savedTx.getId() + "\"}"
        );

        return TransactionResponse.fromEntity(savedTx);
    }

    /**
     * Mathematical deadlock prevention: always acquires row-level locks in deterministic ascending ID order.
     */
    private AccountPair lockAccountsInOrder(UUID idA, UUID idB) {
        if (idA.equals(idB)) {
            throw new InvalidRequestException("Cannot lock the same account twice");
        }

        boolean idAFirst = idA.compareTo(idB) < 0;
        UUID firstId = idAFirst ? idA : idB;
        UUID secondId = idAFirst ? idB : idA;

        Account first = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + firstId));
        Account second = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + secondId));

        return idAFirst ? new AccountPair(first, second) : new AccountPair(second, first);
    }

    private void validateRoleCanMoveMoney(UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.AUDITOR) {
            throw new AccessDeniedException("Auditor accounts have read-only access and are prohibited from moving money");
        }
    }

    private void validateOwnership(Account account, UserPrincipal currentUser) {
        if (currentUser.getRole() == Role.ACCOUNT_HOLDER) {
            if (account.getOwnerId() == null || !account.getOwnerId().equals(currentUser.getId())) {
                // Must return 404 (not 403) to prevent account existence probing
                throw new ResourceNotFoundException("Account not found with ID: " + account.getId());
            }
        }
    }

    private void validateAccountActive(Account account) {
        if (account.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException("Account " + account.getAccountNumber() + " is FROZEN. Transactions are blocked.");
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AccountClosedException("Account " + account.getAccountNumber() + " is CLOSED. Transactions are blocked.");
        }
    }

    private void validateAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new InvalidRequestException("Transaction amount must be strictly greater than zero");
        }
        if (amount > MAX_TRANSACTION_AMOUNT) {
            throw new InvalidRequestException("Transaction amount exceeds the maximum permissible limit of 1 Crore INR");
        }
    }

    private record AccountPair(Account first, Account second) {}
}
