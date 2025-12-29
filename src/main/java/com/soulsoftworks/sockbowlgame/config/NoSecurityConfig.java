package com.soulsoftworks.sockbowlgame.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration when authentication is disabled.
 *
 * This configuration is only active when sockbowl.auth.enabled=false.
 * It disables all security and permits all requests.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "false", matchIfMissing = false)
public class NoSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF protection
            .csrf(AbstractHttpConfigurer::disable)

            // Enable CORS
            .cors(Customizer.withDefaults())

            // Permit all requests
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
