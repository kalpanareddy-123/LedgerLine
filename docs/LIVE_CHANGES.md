# LedgerLine - Live Interview Coding Modifications (Practice Guide)

In a live interview defence, interviewers frequently say:
*"This code looks good, but let's see you modify it live on screen."*

Below are **10 practice modifications** covering security, domain rules, validation, and concurrency. For each scenario, follow the exact files to touch, code changes to make, and tests to run.

---

### Modification 1: Lower the Maximum Transaction Amount to ₹50,000 (5,000,000 Paise)
- **Scenario**: Regulatory rules mandate capping single transfers at ₹50,000.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/resources/application.yml`
  2. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java`
- **What to Change**:
  In `application.yml`:
  ```yaml
  ledgerline:
    max-transaction-amount: ${MAX_TRANSACTION_AMOUNT:5000000}
  ```
  In `LedgerService.java`:
  ```java
  if (amount > maxTransactionAmount) {
      throw new ValidationException("Transaction amount exceeds maximum permissible limit of ₹50,000");
  }
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=LedgerEngineTest#validationRejections
  ```

---

### Modification 2: Add a Minimum Transfer Amount Rule (₹1.00 / 100 Paise)
- **Scenario**: Prevent spam micro-transactions by enforcing a minimum transfer threshold of ₹1.00.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java`
- **What to Change**:
  In `LedgerService.transfer(...)`:
  ```java
  if (request.amount() < 100L) {
      throw new ValidationException("Transfer amount must be at least ₹1.00 (100 paise)");
  }
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=LedgerEngineTest#validationRejections
  ```

---

### Modification 3: Add a New Account Status `RESTRICTED` (Credits Allowed, Debits Blocked)
- **Scenario**: A compliance freeze where an account can receive incoming deposits/transfers, but outgoing withdrawals and transfers are blocked.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/domain/AccountStatus.java`
  2. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java`
- **What to Change**:
  In `AccountStatus.java`:
  ```java
  public enum AccountStatus {
      ACTIVE,
      FROZEN,
      CLOSED,
      RESTRICTED // New status
  }
  ```
  In `LedgerService.java`:
  ```java
  // In withdraw() and transfer() source checks:
  if (account.getStatus() == AccountStatus.RESTRICTED) {
      throw new AccountInactiveException("Account is RESTRICTED: Outgoing debits are prohibited");
  }
  // In deposit() and transfer() destination checks:
  // Allow RESTRICTED accounts to receive funds!
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=AccountAndAdminTest
  ```

---

### Modification 4: Increase Login Rate Limit Lockout from 5 Minutes to 15 Minutes
- **Scenario**: Heightened security posture requires extending the IP lockout period to 15 minutes.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/security/LoginRateLimiter.java`
- **What to Change**:
  ```java
  // Change LOCKOUT_DURATION_SECONDS from 300 to 900
  private static final long LOCKOUT_DURATION_SECONDS = 900; // 15 minutes
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=AuthAndSecurityTest#bruteForceRateLimiterLocksOutAfterFiveAttempts
  ```

---

### Modification 5: Add a Transaction Reference Minimum Length Check (Min 3 Characters)
- **Scenario**: Ensure transaction references are descriptive for compliance auditing.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/dto/TransferRequest.java`
  2. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/dto/DepositRequest.java`
- **What to Change**:
  In `TransferRequest.java`:
  ```java
  @Size(min = 3, max = 255, message = "Reference must be between 3 and 255 characters")
  String reference
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=LedgerEngineTest#validationRejections
  ```

---

### Modification 6: Extend Token Expiry from 30 Minutes to 60 Minutes
- **Scenario**: User session duration adjusted from 30 minutes to 1 hour.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/resources/application.yml`
  2. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/security/JwtService.java`
- **What to Change**:
  In `application.yml`:
  ```yaml
  security:
    jwt:
      expiration-minutes: ${JWT_EXPIRATION_MINUTES:60}
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=AuthAndSecurityTest#expiredTokenReturns401
  ```

---

### Modification 7: Add Audit Logging for Account Status Updates (ACTIVE -> FROZEN)
- **Scenario**: Regulators require an immutable audit trail whenever an admin freezes or closes an account.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/AccountService.java`
- **What to Change**:
  In `AccountService.updateAccountStatus(...)`:
  ```java
  auditService.record(
      "UPDATE_ACCOUNT_STATUS",
      "ACCOUNT",
      id.toString(),
      adminPrincipal.getId(),
      "Status changed to " + newStatus,
      AuditLog.AuditStatus.SUCCESS
  );
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=StatementReconciliationAuditTest#deniedActionWritesAuditLogRow
  ```

---

### Modification 8: Add Endpoint to Fetch Transaction Details by Transaction ID
- **Scenario**: Allow customers to inspect a single past transaction receipt.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/controller/StatementController.java`
  2. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java`
- **What to Change**:
  In `StatementController.java`:
  ```java
  @GetMapping("/transactions/{txId}")
  public ResponseEntity<TransactionResponse> getTransactionById(
          @PathVariable UUID txId,
          @AuthenticationPrincipal UserPrincipal currentUser) {
      return ResponseEntity.ok(ledgerService.getTransaction(txId, currentUser));
  }
  ```
  In `LedgerService.java`:
  ```java
  public TransactionResponse getTransaction(UUID txId, UserPrincipal currentUser) {
      Transaction tx = transactionRepository.findById(txId)
          .orElseThrow(() -> new ResourceNotFoundException("Transaction not found"));
      // Ownership check: Caller must own source or destination account (or be ADMIN/AUDITOR)
      return TransactionResponse.from(tx);
  }
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=LedgerEngineTest
  ```

---

### Modification 9: Prohibit Transfers to System Accounts
- **Scenario**: Customers should only transfer to customer accounts; direct transfer to `SYS_CASH_CLEARING` must be rejected.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java`
- **What to Change**:
  In `LedgerService.transfer(...)`:
  ```java
  if (destinationAccount.isSystem()) {
      throw new ValidationException("Direct transfers to system clearing accounts are prohibited");
  }
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=LedgerEngineTest#validationRejections
  ```

---

### Modification 10: Include Currency in Statement CSV Export
- **Scenario**: Compliance requires CSV exports to include an explicit ISO 4217 Currency column.
- **Files to Touch**:
  1. `backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/StatementService.java`
- **What to Change**:
  In `StatementService.generateCsv(...)`:
  ```java
  csv.append("Date,Entry ID,Direction,Amount (INR),Balance After (INR),Currency,Reference\n");
  // In loop:
  csv.append(String.format("%s,%s,%s,%.2f,%.2f,%s,\"%s\"\n",
      entry.getCreatedAt(),
      entry.getId(),
      entry.getDirection(),
      entry.getAmount() / 100.0,
      entry.getBalanceAfter() / 100.0,
      "INR", // Currency column
      entry.getReference()
  ));
  ```
- **Test to Run**:
  ```bash
  ./mvnw test -Dtest=StatementReconciliationAuditTest#csvExportOwnership404Protection
  ```
