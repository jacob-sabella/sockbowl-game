package org.springframework.boot.autoconfigure.orm.jpa;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Spring Boot 4.0 compatibility bridge.
 * Empty placeholder - actual config is org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
 */
@Deprecated
@AutoConfiguration(after = org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class)
public class HibernateJpaAutoConfiguration {
	// Empty bridge class - all configuration is handled by the new HibernateJpaAutoConfiguration
}
