package com.soulsoftworks.sockbowlgame.security.stomp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.soulsoftworks.sockbowlgame.config.JwtDecoderConfig;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A throwaway "Keycloak" for unit tests: an RSA key whose JWK set is served by a
 * MockWebServer, and the real audience-bound decoder from
 * {@link JwtDecoderConfig} pointed at it. Tokens minted here are real signed
 * JWTs, so expiry, issuer and audience failures come from the production
 * validator chain.
 */
final class TestJwtIssuer implements AutoCloseable {

    static final String AUDIENCE = "sockbowl-api";

    private final MockWebServer server = new MockWebServer();
    private final RSAKey key;
    private final String issuer;
    private final JwtDecoder decoder;

    TestJwtIssuer() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("stomp-test").generate();
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder().code(200)
                        .setHeader("Content-Type", "application/json").body(jwks).build();
            }
        });
        server.start();
        issuer = server.url("/realms/sockbowl").toString();
        decoder = new JwtDecoderConfig().jwtDecoder(issuer + "/protocol/openid-connect/certs", issuer, AUDIENCE);
    }

    JwtDecoder decoder() {
        return decoder;
    }

    /** A user token for {@code sub} with the given realm roles, valid for 5 minutes. */
    String user(String sub, String... roles) {
        return token(sub, "sockbowl-game", List.of(AUDIENCE), Instant.now().plusSeconds(300), roles);
    }

    String token(String sub, String azp, List<String> aud, Instant expiresAt, String... roles) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .issuer(issuer)
                    .issueTime(Date.from(expiresAt.minusSeconds(600)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("azp", azp)
                    .claim("realm_access", Map.of("roles", List.of(roles)));
            if (aud != null) {
                claims.audience(aud);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
