package com.soulsoftworks.sockbowlgame.security.stomp;

/**
 * Outcome of a {@link StompInboundGuard} check.
 */
public enum StompGuardResult {
    /** Let the frame continue to the next guard / the broker / the handlers. */
    PASS,
    /**
     * Silently drop the frame: the interceptor returns {@code null} from
     * {@code preSend}, no later guard runs, and the socket stays open. The guard
     * is responsible for any {@code /user/queue/errors} notice it wants to send.
     */
    DROP
}
