package org.springframework.boot.autoconfigure.jdbc;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Spring Boot 4.0 compatibility bridge.
 * Empty placeholder - actual config is org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
 */
@Deprecated
@AutoConfiguration(after = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
public class DataSourceAutoConfiguration {
	// Empty bridge class - all configuration is handled by the new DataSourceAutoConfiguration
}
