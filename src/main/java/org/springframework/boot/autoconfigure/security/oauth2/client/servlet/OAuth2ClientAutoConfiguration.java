package org.springframework.boot.autoconfigure.security.oauth2.client.servlet;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Spring Boot 4.0 compatibility bridge.
 * Empty placeholder - actual config is org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration
 */
@Deprecated
@AutoConfiguration(after = org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class)
public class OAuth2ClientAutoConfiguration {
	// Empty bridge class - all configuration is handled by the new OAuth2ClientAutoConfiguration
}
