package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageTouchTracker;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Last-seen tracking for signed-in players (plan m4-limits section 2.4, WP-G3):
 * a <b>post-auth</b> {@link StompInboundGuard} ({@link #ORDER} {@code = 300})
 * that, on an authenticated non-guest CONNECT, calls
 * {@link UsageTouchTracker#touch} with the subject and the handshake address,
 * so the admin usage view can list the IPs a user plays from. The tracker
 * throttles its own Redis writes. It never rejects: it always returns
 * {@link StompGuardResult#PASS} and swallows tracker failures.
 */
@Slf4j
@Component
public class StompUsageTouchGuard implements StompInboundGuard {

    public static final int ORDER = 300;

    private final UsageTouchTracker tracker;
    private final LimitSubjectResolver subjectResolver;

    public StompUsageTouchGuard(UsageTouchTracker tracker, LimitSubjectResolver subjectResolver) {
        this.tracker = tracker;
        this.subjectResolver = subjectResolver;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public StompGuardResult check(StompHeaderAccessor accessor, StompPrincipal principal) {
        StompCommand command = accessor.getCommand();
        boolean connect = command == StompCommand.CONNECT || command == StompCommand.STOMP;
        if (!connect || principal == null || principal.isGuest() || principal.getKeycloakId() == null) {
            return StompGuardResult.PASS;
        }
        try {
            String ip = ClientIpHandshakeInterceptor.clientIp(accessor.getSessionAttributes());
            tracker.touch(subjectResolver.forIdentity(principal.getKeycloakId(), principal.getAuthorities(),
                    false, ip));
        } catch (RuntimeException e) {
            log.debug("Usage touch failed for {}: {}", principal, e.toString());
        }
        return StompGuardResult.PASS;
    }
}
