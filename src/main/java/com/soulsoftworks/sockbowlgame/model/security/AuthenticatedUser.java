package com.soulsoftworks.sockbowlgame.model.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable value object representing the security identity resolved at the
 * application edge (REST filter chain or WebSocket argument resolver).
 *
 * <p>It is the single, validated representation of "who is making this request"
 * that flows from the edge into the domain/authorization layer. It deliberately
 * does NOT carry the raw {@link Jwt} or any framework type so the domain layer
 * stays decoupled from Spring Security.
 *
 * <p>A {@code guest} identity (anonymous / header-secret authenticated player)
 * carries no Keycloak subject and no roles.
 */
public final class AuthenticatedUser {

    private final String keycloakId;
    private final String username;
    private final String email;
    private final Set<String> roles;
    private final boolean guest;

    private AuthenticatedUser(String keycloakId, String username, String email,
                              Set<String> roles, boolean guest) {
        this.keycloakId = keycloakId;
        this.username = username;
        this.email = email;
        this.roles = roles == null ? Collections.emptySet() : Set.copyOf(roles);
        this.guest = guest;
    }

    /**
     * The anonymous / guest identity. Used when authentication is disabled or
     * when a player authenticates only with a header-based player secret.
     */
    public static AuthenticatedUser guest() {
        return new AuthenticatedUser(null, null, null, Collections.emptySet(), true);
    }

    /**
     * Build an authenticated identity from a validated Keycloak access token.
     * Realm roles are read from the standard {@code realm_access.roles} claim.
     */
    @SuppressWarnings("unchecked")
    public static AuthenticatedUser fromJwt(Jwt jwt) {
        if (jwt == null) {
            return guest();
        }
        Set<String> roles = new HashSet<>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realmAccessMap) {
            Object rawRoles = realmAccessMap.get("roles");
            if (rawRoles instanceof Collection<?> roleCollection) {
                for (Object role : roleCollection) {
                    if (role != null) {
                        roles.add(role.toString());
                    }
                }
            }
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            username = jwt.getClaimAsString("name");
        }
        return new AuthenticatedUser(
                jwt.getSubject(),
                username,
                jwt.getClaimAsString("email"),
                roles,
                false
        );
    }

    /**
     * Build an authenticated identity directly from already-extracted authority
     * names (used for the granted-authority based filter-chain flows). Authority
     * names prefixed with {@code ROLE_} are normalised to the bare role name.
     */
    public static AuthenticatedUser of(String keycloakId, String username, String email,
                                       List<String> authorities) {
        Set<String> roles = new HashSet<>();
        if (authorities != null) {
            for (String authority : authorities) {
                if (authority == null) {
                    continue;
                }
                roles.add(authority.startsWith("ROLE_") ? authority.substring(5) : authority);
            }
        }
        return new AuthenticatedUser(keycloakId, username, email, roles, false);
    }

    public String getKeycloakId() {
        return keycloakId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean isGuest() {
        return guest;
    }

    /**
     * True when this identity represents a real, signed-in Keycloak user
     * (i.e. it has a subject and is not a guest).
     */
    public boolean isAuthenticated() {
        return !guest && keycloakId != null;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("admin");
    }
}
