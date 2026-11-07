package com.snovasys.ledgerline.seed;

import com.snovasys.ledgerline.domain.Account;
import com.snovasys.ledgerline.domain.Role;
import com.snovasys.ledgerline.domain.User;
import com.snovasys.ledgerline.dto.DepositRequest;
import com.snovasys.ledgerline.repository.AccountRepository;
import com.snovasys.ledgerline.repository.UserRepository;
import com.snovasys.ledgerline.security.UserPrincipal;
import com.snovasys.ledgerline.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Startup Data Seeder for Development and Demo profiles.
 * Initializes default demo personas:
 * - admin / AdminPass123! (ADMIN)
 * - auditor / AuditorPass123! (AUDITOR)
 * - alice / AlicePass123! (ACCOUNT_HOLDER) with initial ₹1,00,000.00
 * - bob / BobPass123! (ACCOUNT_HOLDER) with initial ₹50,000.00
 *
 * Money movements strictly route through double-entry counterparty clearing
 * so the ledger sum remains strictly zero.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "ledgerline.demo-seed", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final LedgerService ledgerService;

    public DemoDataSeeder(UserRepository userRepository,
                          AccountRepository accountRepository,
                          PasswordEncoder passwordEncoder,
                          LedgerService ledgerService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.ledgerService = ledgerService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.findByUsername("admin").isPresent() && accountRepository.count() > 1) {
            log.info("Demo users and accounts already seeded. Skipping startup seed.");
            return;
        }

        log.info("Seeding initial demo data for LedgerLine...");

        // 1. Create or retrieve Admin
        User admin = userRepository.findByUsername("admin").orElseGet(() ->
            userRepository.save(new User(
                "admin",
                "admin@ledgerline.local",
                passwordEncoder.encode("AdminPass123!"),
                Role.ADMIN
            ))
        );

        // 2. Create or retrieve Auditor
        userRepository.findByUsername("auditor").orElseGet(() ->
            userRepository.save(new User(
                "auditor",
                "auditor@ledgerline.local",
                passwordEncoder.encode("AuditorPass123!"),
                Role.AUDITOR
            ))
        );

        // 3. Create or retrieve Account Holder: Alice
        User alice = userRepository.findByUsername("alice").orElseGet(() ->
            userRepository.save(new User(
                "alice",
                "alice@ledgerline.local",
                passwordEncoder.encode("AlicePass123!"),
                Role.ACCOUNT_HOLDER
            ))
        );

        Account aliceAcc = accountRepository.findByOwnerId(alice.getId()).stream().findFirst().orElseGet(() ->
            accountRepository.save(new Account(
                alice.getId(),
                "ACC-ALICE-01",
                0L,
                false
            ))
        );

        // 4. Create or retrieve Account Holder: Bob
        User bob = userRepository.findByUsername("bob").orElseGet(() ->
            userRepository.save(new User(
                "bob",
                "bob@ledgerline.local",
                passwordEncoder.encode("BobPass123!"),
                Role.ACCOUNT_HOLDER
            ))
        );

        Account bobAcc = accountRepository.findByOwnerId(bob.getId()).stream().findFirst().orElseGet(() ->
            accountRepository.save(new Account(
                bob.getId(),
                "ACC-BOB-01",
                0L,
                false
            ))
        );

        // 5. Seed initial balances through the double-entry engine as the respective account holders
        UserPrincipal alicePrincipal = new UserPrincipal(
                alice.getId(),
                alice.getUsername(),
                alice.getEmail(),
                alice.getRole(),
                true
        );

        UserPrincipal bobPrincipal = new UserPrincipal(
                bob.getId(),
                bob.getUsername(),
                bob.getEmail(),
                bob.getRole(),
                true
        );

        // Deposit ₹1,00,000.00 (10,000,000 paise) into Alice's account
        ledgerService.deposit(
                aliceAcc.getId(),
                new DepositRequest(10000000L, "Demo Opening Balance (Alice)"),
                UUID.randomUUID().toString(),
                alicePrincipal
        );

        // Deposit ₹50,000.00 (5,000,000 paise) into Bob's account
        ledgerService.deposit(
                bobAcc.getId(),
                new DepositRequest(5000000L, "Demo Opening Balance (Bob)"),
                UUID.randomUUID().toString(),
                bobPrincipal
        );

        log.info("Successfully seeded demo users and accounts: admin, auditor, alice (ACC-ALICE-01), bob (ACC-BOB-01).");
    }
}
