package com.soulsoftworks.sockbowlgame.security.stomp;

import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Bearer-token helpers shared by CONNECT authentication and the SEND refresh
 * path, so both map decoder failures to the same {@link StompErrorCode}s.
 */
final class StompTokens {

    static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";

    private StompTokens() {
    }

    /** The raw {@code Authorization} native header ({@code authorization} accepted too), or null. */
    static String authorizationHeader(StompHeaderAccessor accessor) {
        String value = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (value == null) {
            value = accessor.getFirstNativeHeader("authorization");
        }
        return value;
    }

    /**
     * The token in a {@code Bearer} header, or null when the header is absent.
     *
     * @throws StompRejectedException {@code INVALID_CREDENTIALS} when a header is
     *                                present but is not a non-empty bearer token
     */
    static String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        if (header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = header.substring(BEARER.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "Malformed Authorization header");
    }

    /**
     * Decode and validate with the shared, audience-bound decoder.
     *
     * @throws StompRejectedException {@code TOKEN_EXPIRED} for an expired token,
     *                                {@code INVALID_CREDENTIALS} for anything else
     *                                the decoder rejects (signature, issuer,
     *                                audience, malformed), {@code INTERNAL} when no
     *                                decoder is configured
     */
    static Jwt decode(JwtDecoder decoder, String token) {
        if (decoder == null) {
            throw new StompRejectedException(StompErrorCode.INTERNAL, "Token validation is not configured");
        }
        try {
            Jwt jwt = decoder.decode(token);
            if (jwt == null) {
                throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "Invalid token");
            }
            return jwt;
        } catch (JwtValidationException ex) {
            if (isExpiry(ex)) {
                throw new StompRejectedException(StompErrorCode.TOKEN_EXPIRED, "Token expired");
            }
            throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "Invalid token");
        } catch (JwtException ex) {
            throw new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "Invalid token");
        }
    }

    /** Spring's {@code JwtTimestampValidator} reports expiry as "Jwt expired at ...". */
    private static boolean isExpiry(JwtValidationException ex) {
        for (OAuth2Error error : ex.getErrors()) {
            String description = error.getDescription();
            if (description != null && description.toLowerCase().contains("expired")) {
                return true;
            }
        }
        String message = ex.getMessage();
        return message != null && message.toLowerCase().contains("expired");
    }
}
