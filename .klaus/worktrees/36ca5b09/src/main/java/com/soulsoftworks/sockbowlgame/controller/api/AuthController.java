package com.soulsoftworks.sockbowlgame.controller.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication controller for OAuth2/OIDC login via Keycloak.
 * Provides endpoints for login, user information, and authentication status.
 *
 * Only active when sockbowl.auth.enabled=true.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class AuthController {

    /**
     * Redirects to Keycloak login page.
     * This triggers the OAuth2 authorization code flow.
     *
     * @return RedirectView to OAuth2 authorization endpoint
     */
    @GetMapping("/login")
    public RedirectView login() {
        return new RedirectView("/oauth2/authorization/keycloak");
    }

    /**
     * OAuth2 login success callback.
     * Returns user information from the JWT token.
     *
     * @param jwt JWT token from Keycloak (injected by Spring Security)
     * @return User information including access token
     */
    @GetMapping("/success")
    public ResponseEntity<Map<String, Object>> success(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("userId", jwt.getSubject());
        response.put("email", jwt.getClaimAsString("email"));
        response.put("name", jwt.getClaimAsString("name"));
        response.put("accessToken", jwt.getTokenValue());
        response.put("expiresAt", jwt.getExpiresAt());

        return ResponseEntity.ok(response);
    }

    /**
     * Get current authenticated user information.
     *
     * @param jwt JWT token from Keycloak
     * @return User information
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("keycloakId", jwt.getSubject());
        response.put("email", jwt.getClaimAsString("email"));
        response.put("name", jwt.getClaimAsString("name"));
        response.put("preferredUsername", jwt.getClaimAsString("preferred_username"));

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint to verify authentication is enabled.
     *
     * @return Status indicating authentication is enabled
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> response = new HashMap<>();
        response.put("authEnabled", true);
        response.put("provider", "keycloak");
        return ResponseEntity.ok(response);
    }
}
