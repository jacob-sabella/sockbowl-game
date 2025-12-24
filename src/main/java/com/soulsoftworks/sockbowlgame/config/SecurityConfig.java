package com.soulsoftworks.sockbowlgame.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for Keycloak OAuth2/OIDC authentication.
 *
 * This configuration is only active when sockbowl.auth.enabled=true.
 * When disabled, the application operates in guest-only mode with no authentication.
 *
 * Features:
 * - OAuth2 login via Keycloak
 * - JWT resource server for API authentication
 * - Stateless session management
 * - Dual authentication support (JWT for authenticated users, headers for guests)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for WebSocket and guest endpoints
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/sockbowl-game/**")  // WebSocket endpoint
                .ignoringRequestMatchers("/api/v1/session/**"))  // Guest session endpoints

            // Enable CORS - must come before other security filters
            .cors(Customizer.withDefaults())

            // Handle authentication exceptions without redirects for API endpoints
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    // For API requests, return 401 instead of redirecting to login
                    if (request.getRequestURI().startsWith("/api/")) {
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                    } else {
                        // For non-API requests, allow default OAuth2 redirect behavior
                        response.sendRedirect("/api/v1/auth/login");
                    }
                })
            )

            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints (guest mode - backward compatible)
                .requestMatchers("/api/v1/session/create-new-game-session").permitAll()
                .requestMatchers("/api/v1/session/join-game-session-by-code").permitAll()
                .requestMatchers("/sockbowl-game/**").permitAll()  // WebSocket endpoint

                // OAuth2/Authentication endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/login/**").permitAll()
                .requestMatchers("/oauth2/**").permitAll()

                // Protected user endpoints (require authentication)
                .requestMatchers("/api/v1/user/**").authenticated()
                .requestMatchers("/api/v1/session/join-game-session-authenticated").authenticated()

                // All other requests are permitted (for backward compatibility)
                .anyRequest().permitAll()
            )

            // OAuth2 Login configuration
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/api/v1/auth/login")
                .defaultSuccessUrl("/api/v1/auth/success")
            )

            // OAuth2 Resource Server (JWT validation)
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(Customizer.withDefaults())
            )

            // Stateless session management (no server-side sessions)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    /**
     * JWT decoder for validating Keycloak tokens.
     * Automatically configured from the issuer URI.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }
}
