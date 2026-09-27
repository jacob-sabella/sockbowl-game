package com.soulsoftworks.sockbowlgame.security.stomp;

/**
 * Typed STOMP failure codes (plan m2-auth section 2.5). These are a frozen wire
 * contract shared with ng (WP-N3) and M4:
 * <ul>
 *   <li>values are never renamed or removed, only appended (M4 appends
 *       {@code RATE_LIMITED}, {@code QUOTA_EXCEEDED} and {@code IP_BANNED});</li>
 *   <li>codes always travel as their UPPER_SNAKE {@link #name()}, in the ERROR
 *       frame's {@code message} and {@code x-sockbowl-error} headers, in the
 *       ERROR body's {@code code} field, and in {@code StompError.code} on
 *       {@code /user/queue/errors}.</li>
 * </ul>
 */
public enum StompErrorCode {
    /** No credentials, or a frame other than CONNECT on an unauthenticated socket. */
    AUTH_REQUIRED,
    /** Wrong player secret, an undecodable/invalid JWT, or a service-account token. */
    INVALID_CREDENTIALS,
    /** The JWT has expired (CONNECT) or the principal's token expired without a refresh (SEND). */
    TOKEN_EXPIRED,
    /** The authenticated user has an active ban. */
    BANNED,
    /** No game session with the given id. */
    SESSION_NOT_FOUND,
    /** The player id is not part of the game session. */
    PLAYER_NOT_IN_SESSION,
    /** The token subject or a frame's identity headers do not match the connected player. */
    IDENTITY_MISMATCH,
    /** SEND outside {@code /app/**}, or SUBSCRIBE outside the caller's own queues. */
    FORBIDDEN_DESTINATION,
    /** Anything unexpected; never carries a stack trace on the wire. */
    INTERNAL
}
