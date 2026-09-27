package com.soulsoftworks.sockbowlgame.controller.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication information endpoints.
 *
 * <p>There is deliberately no server-side login flow here: ng runs the OIDC
 * authorization-code + PKCE flow against Keycloak itself and calls this API with
 * a bearer token. The former {@code /login} and {@code /success} endpoints (the
 * latter echoed the raw access token back in a response body) are gone
 * (AUTH-08).
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class AuthController {

    /**
     * Get current authenticated user information. Requires a user (not a
     * service-account) bearer token; see {@code SecurityConfig}.
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
     * Public: lets clients discover that authentication is enabled.
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
