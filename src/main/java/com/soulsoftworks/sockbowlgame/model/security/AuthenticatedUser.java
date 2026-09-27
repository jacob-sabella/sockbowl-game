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
 *
 * <p>A {@code service} identity is a client-credentials token issued to a
 * backend (the {@code sockbowl-game-backend} service account). It is a valid
 * token but <b>not a user</b>: it may not host, join, or reach user endpoints
 * (plan section 2.2). It is recognised by its {@code azp} (authorized party)
 * claim, or {@code client_id} as a fallback, equalling the configured service
 * client id ({@code sockbowl.auth.service-client-id}).
 */
public final class AuthenticatedUser {

    private final String keycloakId;
    private final String username;
    private final String email;
    private final Set<String> roles;
    private final boolean guest;
    private final boolean service;

    /** Default {@code sockbowl.auth.service-client-id}. */
    public static final String DEFAULT_SERVICE_CLIENT_ID = "sockbowl-game-backend";

    private AuthenticatedUser(String keycloakId, String username, String email,
                              Set<String> roles, boolean guest, boolean service) {
        this.keycloakId = keycloakId;
        this.username = username;
        this.email = email;
        this.roles = roles == null ? Collections.emptySet() : Set.copyOf(roles);
        this.guest = guest;
        this.service = service;
    }

    /**
     * The anonymous / guest identity. Used when authentication is disabled or
     * when a player authenticates only with a header-based player secret.
     */
    public static AuthenticatedUser guest() {
        return new AuthenticatedUser(null, null, null, Collections.emptySet(), true, false);
    }

    /**
     * Build an authenticated identity from a validated Keycloak access token,
     * recognising the default service client id
     * ({@value #DEFAULT_SERVICE_CLIENT_ID}) as a service identity. Prefer
     * {@link #fromJwt(Jwt, String)} with the configured id where one is at hand
     * (see {@code GameAuthorizationPolicy#identityOf}).
     */
    public static AuthenticatedUser fromJwt(Jwt jwt) {
        return fromJwt(jwt, DEFAULT_SERVICE_CLIENT_ID);
    }

    /**
     * Build an authenticated identity from a validated Keycloak access token.
     * Realm roles are read from the standard {@code realm_access.roles} claim
     * (Keycloak expands composites there, so permission roles such as
     * {@code game:host} appear directly).
     *
     * @param serviceClientId the backend service client id; a token whose
     *                        {@code azp} (or {@code client_id}) equals it is a
     *                        service identity
     */
    public static AuthenticatedUser fromJwt(Jwt jwt, String serviceClientId) {
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
                false,
                isServiceToken(jwt, serviceClientId)
        );
    }

    /**
     * True when the token was issued to the backend service client (a
     * client-credentials token) rather than to a user.
     */
    public static boolean isServiceToken(Jwt jwt, String serviceClientId) {
        if (jwt == null || serviceClientId == null || serviceClientId.isBlank()) {
            return false;
        }
        String azp = jwt.getClaimAsString("azp");
        if (azp != null) {
            return serviceClientId.equals(azp);
        }
        return serviceClientId.equals(jwt.getClaimAsString("client_id"));
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
        return new AuthenticatedUser(keycloakId, username, email, roles, false, false);
    }

    /**
     * A backend service-account identity (client-credentials token) with the
     * given subject and raw authority names.
     */
    public static AuthenticatedUser service(String subject, List<String> authorities) {
        AuthenticatedUser base = of(subject, null, null, authorities);
        return new AuthenticatedUser(subject, null, null, base.roles, false, true);
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

    /**
     * The raw authority names this identity holds (realm roles and permission
     * roles, never {@code ROLE_}-prefixed). Same set as {@link #getRoles()};
     * named for callers that reason in Spring Security authority terms (the
     * STOMP principal, {@code packet:manage-any} checks).
     */
    public Set<String> getAuthorities() {
        return roles;
    }

    public boolean isGuest() {
        return guest;
    }

    /**
     * True when this identity is a backend service account (client-credentials
     * token), not a user. Service identities are never allowed to act as a
     * player or reach user endpoints.
     */
    public boolean isService() {
        return service;
    }

    /**
     * True when this identity carries a validated token with a subject (a user
     * or a service account; see {@link #isService()} to tell them apart).
     */
    public boolean isAuthenticated() {
        return !guest && keycloakId != null;
    }

    /**
     * True when this identity is a real, signed-in Keycloak <b>user</b>:
     * authenticated and not a service account.
     */
    public boolean isUser() {
        return isAuthenticated() && !service;
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * Exact match against a raw granted-authority name (e.g. {@code game:host},
     * {@code user:ban}) as opposed to a {@code ROLE_}-prefixed realm role. Since
     * {@link #of(String, String, String, List)} and {@link #fromJwt(Jwt)} both
     * normalise {@code ROLE_}-prefixed authorities down to their bare name, and
     * fine-grained permission authorities are never prefixed, this can be checked
     * against the same underlying set of names.
     */
    public boolean hasAuthority(String authority) {
        return roles.contains(authority);
    }

    public boolean isAdmin() {
        return hasRole("admin");
    }
}
