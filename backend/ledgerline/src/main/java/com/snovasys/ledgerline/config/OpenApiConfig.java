package com.snovasys.ledgerline.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI ledgerLineOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerLine API - Secure Double-Entry Money Movement Engine")
                        .version("1.0.0")
                        .description("""
                                ## Overview
                                **LedgerLine** is an enterprise-grade double-entry financial ledger and money movement system.
                                
                                ### Core Architecture Principles:
                                - **Paise Minor Units (BIGINT)**: All monetary figures are stored and processed in minor units (1 INR = 100 paise) avoiding floating-point rounding errors.
                                - **Double-Entry Invariant**: Every deposit, withdrawal, and transfer creates paired DEBIT and CREDIT entries that sum to exactly 0.
                                - **Deterministic Deadlock-Free Pessimistic Locking**: Concurrent multi-account transfers lock rows strictly in ascending Account UUID order.
                                - **Database-Guaranteed Idempotency**: State-changing operations require an `Idempotency-Key` UUID header. A local CompletableFuture barrier coordinates single-node concurrency, while PostgreSQL `UNIQUE(user_id, key)` provides cross-instance safety.
                                - **Append-Only Immutability**: Database triggers forbid UPDATE or DELETE on ledger entries and audit log rows.
                                - **Zero-Leak Authorization**: Non-owned accounts return 404 NOT_FOUND to prevent account enumeration.
                                """)
                        .contact(new Contact().name("Snovasys Hiring Challenge Team"))
                        .license(new License().name("Apache 2.0").url("https://springdoc.org")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Enter your JWT token obtained from `/api/auth/login`.")));
    }
}
