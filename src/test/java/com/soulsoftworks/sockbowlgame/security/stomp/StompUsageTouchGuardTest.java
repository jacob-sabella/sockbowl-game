package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.ratelimit.ClientIpResolver;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitProperties;
import com.soulsoftworks.sockbowlgame.ratelimit.Tier;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageTouchTracker;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Last-seen IPs from STOMP (plan m4-limits section 2.4): the post-auth touch
 * guard records signed-in CONNECTs only, never rejects, and never fails a frame.
 */
class StompUsageTouchGuardTest {

    private final List<LimitSubject> touched = new ArrayList<>();
    private final LimitSubjectResolver resolver =
            new LimitSubjectResolver(new ClientIpResolver(), new RateLimitProperties(), "sockbowl-game-backend");
    private final StompUsageTouchGuard guard = new StompUsageTouchGuard(touched::add, resolver);

    private static StompHeaderAccessor frame(StompCommand command) {
        StompHeaderAccessor accessor = StompTestFrames.frame(command, command == StompCommand.SEND ? "/app/x" : null);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ClientIpHandshakeInterceptor.CLIENT_IP, "203.0.113.5");
        accessor.setSessionAttributes(attributes);
        return accessor;
    }

    @Test
    void runsPostAuthAtOrder300() {
        assertThat(guard.order()).isEqualTo(300);
    }

    @Test
    void signedInConnectIsTouchedWithTierAndAddress() {
        StompPrincipal author = StompPrincipal.user("g1", "p1", "kc-author", Set.of("author", "player"), null);
        assertThat(guard.check(frame(StompCommand.CONNECT), author)).isEqualTo(StompGuardResult.PASS);
        assertThat(touched).containsExactly(new LimitSubject("kc-author", "203.0.113.5", Tier.AUTHOR));
    }

    @Test
    void guestsAndOtherFramesAreNotTouched() {
        StompPrincipal user = StompPrincipal.user("g1", "p1", "kc-u", Set.of("player"), null);
        assertThat(guard.check(frame(StompCommand.CONNECT), StompPrincipal.guest("g1", "p2")))
                .isEqualTo(StompGuardResult.PASS);
        assertThat(guard.check(frame(StompCommand.SEND), user)).isEqualTo(StompGuardResult.PASS);
        assertThat(guard.check(frame(StompCommand.CONNECT), null)).isEqualTo(StompGuardResult.PASS);
        assertThat(touched).isEmpty();
    }

    @Test
    void trackerFailuresNeverRejectTheFrame() {
        UsageTouchTracker broken = s -> {
            throw new IllegalStateException("redis down");
        };
        StompUsageTouchGuard failing = new StompUsageTouchGuard(broken, resolver);
        StompPrincipal user = StompPrincipal.user("g1", "p1", "kc-u", Set.of("player"), null);
        assertThat(failing.check(frame(StompCommand.CONNECT), user)).isEqualTo(StompGuardResult.PASS);
    }
}
