package com.soulsoftworks.sockbowlgame.ratelimit;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the {@link LimitSubject} for a caller (plan m4-limits section 2.1).
 *
 * <p>Identity and roles come from M2's {@link AuthenticatedUser} (which reads
 * {@code realm_access.roles} and flags the backend service account by
 * {@code azp == sockbowl.auth.service-client-id}); this class only maps that
 * to a {@link Tier}. A token whose {@code azp} is listed in
 * {@code sockbowl.ratelimit.service-clients} (default: the same service client
 * id) is also SERVICE.
 */
@Component
public class LimitSubjectResolver {

    private final ClientIpResolver clientIpResolver;
    private final String serviceClientId;
    private final Set<String> serviceClients;

    public LimitSubjectResolver(
            ClientIpResolver clientIpResolver,
            RateLimitProperties properties,
            @Value("${sockbowl.auth.service-client-id:" + AuthenticatedUser.DEFAULT_SERVICE_CLIENT_ID + "}")
            String serviceClientId) {
        this.clientIpResolver = clientIpResolver;
        this.serviceClientId = serviceClientId;
        this.serviceClients = new LinkedHashSet<>(properties.getServiceClients());
    }

    /** The subject of the current servlet request (SecurityContext + remote address). */
    public LimitSubject resolve(HttpServletRequest request) {
        return resolve(SecurityContextHolder.getContext().getAuthentication(), clientIpResolver.resolve(request));
    }

    public LimitSubject resolve(Authentication authentication, String normalizedIp) {
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return LimitSubject.guest(normalizedIp);
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return forJwt(jwt, normalizedIp);
        }
        return LimitSubject.guest(normalizedIp);
    }

    public LimitSubject forJwt(Jwt jwt, String normalizedIp) {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt, serviceClientId);
        boolean service = user.isService() || isListedServiceClient(jwt);
        return forIdentity(user.getKeycloakId(), user.getRoles(), service, normalizedIp);
    }

    public LimitSubject forUser(AuthenticatedUser user, String normalizedIp) {
        if (user == null || !user.isAuthenticated()) {
            return LimitSubject.guest(normalizedIp);
        }
        return forIdentity(user.getKeycloakId(), user.getRoles(), user.isService(), normalizedIp);
    }

    /**
     * From already-extracted identity parts (e.g. a STOMP principal's subject and
     * authorities). A {@code null} subject is a guest.
     */
    public LimitSubject forIdentity(String sub, Collection<String> roles, boolean service, String normalizedIp) {
        if (sub == null || sub.isBlank()) {
            return LimitSubject.guest(normalizedIp);
        }
        return new LimitSubject(sub, normalizedIp, service ? Tier.SERVICE : tierOf(roles));
    }

    /** Highest composite realm role: admin &gt; moderator &gt; author &gt; player (default PLAYER). */
    public static Tier tierOf(Collection<String> roles) {
        if (roles == null) {
            return Tier.PLAYER;
        }
        if (roles.contains("admin")) {
            return Tier.ADMIN;
        }
        if (roles.contains("moderator")) {
            return Tier.MODERATOR;
        }
        if (roles.contains("author")) {
            return Tier.AUTHOR;
        }
        return Tier.PLAYER;
    }

    private boolean isListedServiceClient(Jwt jwt) {
        if (serviceClients.isEmpty()) {
            return false;
        }
        String azp = jwt.getClaimAsString("azp");
        if (azp == null) {
            azp = jwt.getClaimAsString("client_id");
        }
        return azp != null && serviceClients.contains(azp);
    }
}
