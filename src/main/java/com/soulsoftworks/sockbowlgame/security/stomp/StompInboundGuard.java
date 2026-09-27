package com.soulsoftworks.sockbowlgame.security.stomp;

import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/**
 * The extension point for inbound STOMP checks (plan m2-auth section 2.5; a
 * frozen contract for M4). Every Spring bean implementing this interface is
 * run by the single {@link StompInboundInterceptor}, in ascending
 * {@link #order()}.
 *
 * <ul>
 *   <li><b>Pre-auth guards</b> ({@code order() < 0}) run first on every frame.
 *       On CONNECT they run <i>before</i> {@link StompConnectAuthenticator}, so
 *       their principal argument is always {@code null} there; on other frames
 *       they receive the connection's principal (or {@code null}). M4's
 *       {@code StompRateLimitGuard} sits at {@code -100} so floods are rejected
 *       before any JWT decode.</li>
 *   <li><b>Post-auth guards</b> ({@code order() >= 0}) run after CONNECT
 *       authentication. {@link StompDestinationGuard} is at {@code 100}; M4's
 *       {@code StompUsageTouchGuard} goes at {@code 300}.</li>
 * </ul>
 *
 * <p>Results: {@link StompGuardResult#PASS} continues; {@link StompGuardResult#DROP}
 * silently drops the frame (socket stays open); throwing
 * {@link StompRejectedException} is <b>fatal</b> (ERROR frame, socket closed).
 */
public interface StompInboundGuard {

    /** Position in the chain; negative values run before authentication. */
    int order();

    /**
     * @param accessor         the inbound frame's (mutable) header accessor
     * @param principalOrNull  the connection's principal, or {@code null} when it
     *                         has not authenticated (always {@code null} for a
     *                         pre-auth guard on CONNECT)
     * @throws StompRejectedException to reject the frame fatally
     */
    StompGuardResult check(StompHeaderAccessor accessor, StompPrincipal principalOrNull)
            throws StompRejectedException;
}
