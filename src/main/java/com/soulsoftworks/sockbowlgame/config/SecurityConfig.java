package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP security for sockbowl-game when {@code sockbowl.auth.enabled=true}
 * (plan m2-auth section 2.3, AUTH-08).
 *
 * <p>The chain is a stateless bearer-token resource server and is
 * <b>deny-by-default</b>: every route is listed explicitly, and anything else is
 * {@code denyAll()}. There is no server-side login flow ({@code oauth2Login}) any
 * more: ng obtains tokens from Keycloak directly (PKCE) and sends them as
 * {@code Authorization: Bearer}. Tokens are decoded by the shared, audience-bound
 * decoder from {@link JwtDecoderConfig}.
 *
 * <p>Failures never redirect: an unauthenticated request gets a JSON 401 (with
 * the RFC 6750 {@code WWW-Authenticate} header), and an authenticated request
 * without the required authority gets a JSON 403. A request that presents an
 * invalid bearer is rejected with 401 even on the {@code permitAll} guest
 * endpoints.
 *
 * <p>When auth is disabled, {@link NoSecurityConfig} applies instead.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class SecurityConfig {

    static final String SESSION_CREATE = "/api/v1/session/create-new-game-session";
    static final String SESSION_JOIN_BY_CODE = "/api/v1/session/join-game-session-by-code";
    static final String SESSION_JOIN_AUTHENTICATED = "/api/v1/session/join-game-session-authenticated";

    private final String serviceClientId;

    public SecurityConfig(
            @Value("${sockbowl.auth.service-client-id:" + AuthenticatedUser.DEFAULT_SERVICE_CLIENT_ID + "}")
            String serviceClientId) {
        this.serviceClientId = serviceClientId;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint entryPoint = jsonAuthenticationEntryPoint();

        http
            // Stateless, bearer-only API: there is no cookie-based session to
            // forge requests against, so CSRF protection does not apply.
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(jsonAccessDeniedHandler()))

            // First match wins; keep the more specific rules first.
            .authorizeHttpRequests(auth -> auth
                // Error dispatches only render a status a controller already
                // chose (e.g. 404 for an unknown join code); they must not be
                // turned into a 401 by the deny-by-default rule below.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                // Guest endpoints (decision D1). A bearer, if present, is still
                // validated by the resource-server filter (invalid -> 401).
                .requestMatchers(HttpMethod.POST, SESSION_CREATE, SESSION_JOIN_BY_CODE).permitAll()

                // WebSocket handshake only; authentication happens at STOMP CONNECT.
                .requestMatchers("/sockbowl-game", "/sockbowl-game/**").permitAll()

                .requestMatchers(HttpMethod.GET, "/api/v1/auth/status").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()

                // Ban management: moderators (user:ban). Must precede /admin/**.
                .requestMatchers("/api/v1/admin/bans", "/api/v1/admin/bans/**").hasAuthority("user:ban")
                .requestMatchers("/api/v1/admin", "/api/v1/admin/**").hasAuthority("admin:access")

                // User endpoints: a signed-in user, never a service account.
                .requestMatchers("/api/v1/user", "/api/v1/user/**").access(userOnly())
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").access(userOnly())
                .requestMatchers(HttpMethod.POST, SESSION_JOIN_AUTHENTICATED).access(userOnly())

                .anyRequest().denyAll()
            )

            // OAuth2 Resource Server (JWT validation) with Keycloak realm-role
            // mapping so realm_access.roles become authorities.
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(entryPoint)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakJwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * Grants access to an authenticated <b>user</b>: anonymous callers are
     * refused (and so get the 401 entry point), and a backend service-account
     * token is refused with 403 (plan section 2.2).
     */
    AuthorizationManager<RequestAuthorizationContext> userOnly() {
        return new AuthorizationManager<>() {
            @Override
            public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                                 RequestAuthorizationContext context) {
                Authentication auth = authentication.get();
                boolean granted = auth != null
                        && auth.isAuthenticated()
                        && !(auth instanceof AnonymousAuthenticationToken)
                        && !isServiceAuthentication(auth);
                return new AuthorizationDecision(granted);
            }
        };
    }

    private boolean isServiceAuthentication(Authentication auth) {
        return auth.getPrincipal() instanceof Jwt jwt
                && AuthenticatedUser.isServiceToken(jwt, serviceClientId);
    }

    /** JSON 401 with the RFC 6750 {@code WWW-Authenticate} header. Never a redirect. */
    static AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, authException) -> {
            bearer.commence(request, response, authException);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Unauthorized", "Authentication required");
        };
    }

    /** JSON 403. */
    static AccessDeniedHandler jsonAccessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        "Forbidden", "You do not have permission to access this resource");
    }

    private static void writeJson(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * Converts a validated Keycloak JWT into a Spring authentication, mapping the
     * realm roles from the {@code realm_access.roles} claim into {@code ROLE_*}
     * granted authorities (in addition to the default scope-based authorities).
     * This is what makes {@code hasRole("admin")} and {@code @PreAuthorize} work.
     */
    @Bean
    public JwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopesConverter.convert(jwt));
            for (String role : extractRealmRoles(jwt)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                // Also emit the raw realm role name (e.g. "game:host", "user:ban")
                // so fine-grained hasAuthority(...) checks work directly against
                // Keycloak permission-style realm roles.
                authorities.add(new SimpleGrantedAuthority(role));
            }
            return authorities;
        });
        return converter;
    }

    private static Collection<String> extractRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realmAccessMap) {
            Object roles = realmAccessMap.get("roles");
            if (roles instanceof Collection<?> roleCollection) {
                List<String> result = new ArrayList<>();
                for (Object role : roleCollection) {
                    if (role != null) {
                        result.add(role.toString());
                    }
                }
                return result;
            }
        }
        return List.of();
    }
}
