package com.soulsoftworks.sockbowlgame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

/**
 * The single, shared {@link JwtDecoder} for sockbowl-game (AUTH-09, AUTH-18).
 *
 * <p>It is used by the HTTP resource-server chain in {@link SecurityConfig} and,
 * from M2 WP-G2 on, by the STOMP CONNECT authenticator, so both paths apply the
 * exact same checks:
 * <ul>
 *   <li>the signature against the realm's JWK set, fetched lazily from
 *       {@code jwk-set-uri} (there is no OIDC discovery call at boot, so the
 *       service starts while Keycloak is still coming up);</li>
 *   <li>{@code exp}/{@code nbf} and the {@code iss} claim against
 *       {@code issuer-uri};</li>
 *   <li>the {@code aud} claim must contain {@code sockbowl.auth.audience}
 *       ({@code sockbowl-api} by default), so tokens minted for some other
 *       client of the realm are rejected.</li>
 * </ul>
 *
 * <p>Only active when {@code sockbowl.auth.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class JwtDecoderConfig {

    public static final String DEFAULT_AUDIENCE = "sockbowl-api";

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${sockbowl.auth.audience:" + DEFAULT_AUDIENCE + "}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(tokenValidator(issuerUri, audience));
        return decoder;
    }

    /**
     * The validator chain every sockbowl-game token must pass: the Spring
     * defaults (timestamps) plus issuer, plus the audience check.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuerUri, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                audienceValidator(audience));
    }

    /** Requires {@code aud} to be present and to contain {@code audience}. */
    public static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
    }
}
