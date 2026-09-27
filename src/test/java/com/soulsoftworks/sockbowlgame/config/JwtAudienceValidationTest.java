package com.soulsoftworks.sockbowlgame.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the shared decoder built by {@link JwtDecoderConfig} (AUTH-09): tokens
 * are verified against the realm's JWK set (served here by a MockWebServer) and
 * must carry the right issuer and the {@code sockbowl-api} audience.
 */
class JwtAudienceValidationTest {

    private static final String AUDIENCE = "sockbowl-api";

    private MockWebServer server;
    private RSAKey signingKey;
    private String issuer;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();

        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(jwks)
                        .build();
            }
        });
        server.start();

        issuer = server.url("/realms/sockbowl").toString();
        String jwkSetUri = issuer + "/protocol/openid-connect/certs";
        decoder = new JwtDecoderConfig().jwtDecoder(jwkSetUri, issuer, AUDIENCE);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private String token(String iss, List<String> aud, Instant expiresAt) throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("kc-user")
                .issuer(iss)
                .issueTime(Date.from(expiresAt.minusSeconds(600)))
                .expirationTime(Date.from(expiresAt))
                .claim("azp", "sockbowl-game");
        if (aud != null) {
            claims.audience(aud);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private String token(String iss, List<String> aud) throws JOSEException {
        return token(iss, aud, Instant.now().plusSeconds(300));
    }

    @Test
    void rightAudienceIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(token(issuer, List.of(AUDIENCE)));
        assertThat(jwt.getSubject()).isEqualTo("kc-user");
        assertThat(jwt.getAudience()).contains(AUDIENCE);
    }

    @Test
    void audienceAmongOthersIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(token(issuer, List.of("account", AUDIENCE)));
        assertThat(jwt.getAudience()).containsExactly("account", AUDIENCE);
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String wrongAud = token(issuer, List.of("account"));
        assertThatThrownBy(() -> decoder.decode(wrongAud))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("aud");
    }

    @Test
    void missingAudienceIsRejected() throws Exception {
        String noAud = token(issuer, null);
        assertThatThrownBy(() -> decoder.decode(noAud))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String otherIssuer = token("http://evil.example/realms/sockbowl", List.of(AUDIENCE));
        assertThatThrownBy(() -> decoder.decode(otherIssuer))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String expired = token(issuer, List.of(AUDIENCE), Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> decoder.decode(expired))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                new JWTClaimsSet.Builder().subject("kc-user").issuer(issuer).audience(AUDIENCE)
                        .expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        forged.sign(new RSASSASigner(otherKey));
        String serialized = forged.serialize();
        assertThatThrownBy(() -> decoder.decode(serialized))
                .isInstanceOf(org.springframework.security.oauth2.jwt.BadJwtException.class);
    }

    @Test
    void decoderBuildDoesNotContactKeycloak() {
        // Building the bean must not fetch keys (no discovery at boot); keys
        // are fetched on the first decode. setUp() already built one against
        // this server.
        assertThat(server.getRequestCount()).isZero();
    }
}
