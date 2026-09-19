package com.verireview.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables {@code @CreatedDate}/{@code @LastModifiedDate} auditing used by
 * {@code BaseEntity} and {@code AuditLog}. No other configuration in Phase 2.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
