package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.service.UserBannedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A new ban ends the banned user's live STOMP sessions (G-04). */
class StompBanEnforcerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final SimpUserRegistry registry = mock(SimpUserRegistry.class);
    private final MessageChannel outbound = mock(MessageChannel.class);
    private final StompBanEnforcer enforcer =
            new StompBanEnforcer(registry, outbound, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SimpUser user(Principal principal, String... sessionIds) {
        SimpUser user = mock(SimpUser.class);
        when(user.getPrincipal()).thenReturn(principal);
        Set<SimpSession> sessions = new java.util.LinkedHashSet<>();
        for (String id : sessionIds) {
            SimpSession s = mock(SimpSession.class);
            when(s.getId()).thenReturn(id);
            sessions.add(s);
        }
        when(user.getSessions()).thenReturn(sessions);
        return user;
    }

    private void connected(SimpUser... users) {
        when(registry.getUsers()).thenReturn(Set.of(users));
    }

    private List<Message<?>> sentFrames() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Message<?>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Message.class);
        verify(outbound, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void banSendsABannedErrorFrameToEverySessionOfThatUserOnly() {
        connected(
                user(StompPrincipal.user("g1", "p1", "kc-mallory", Set.of(), NOW.plusSeconds(60)), "ws-1"),
                user(StompPrincipal.user("g2", "p9", "kc-mallory", Set.of(), NOW.plusSeconds(60)), "ws-2"),
                user(StompPrincipal.user("g1", "p2", "kc-alice", Set.of(), NOW.plusSeconds(60)), "ws-3"),
                user(StompPrincipal.guest("g1", "p3"), "ws-4"),
                user(() -> "not-a-stomp-principal", "ws-5"));

        enforcer.onUserBanned(new UserBannedEvent("kc-mallory", null));

        List<Message<?>> frames = sentFrames();
        assertThat(frames).hasSize(2);
        assertThat(frames).extracting(f -> StompHeaderAccessor.wrap(f).getSessionId())
                .containsExactlyInAnyOrder("ws-1", "ws-2");
        for (Message<?> frame : frames) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(frame);
            assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
            assertThat(accessor.getMessage()).isEqualTo("BANNED");
            assertThat(accessor.getFirstNativeHeader(SockbowlStompErrorHandler.ERROR_CODE_HEADER)).isEqualTo("BANNED");
            assertThat(new String((byte[]) frame.getPayload(), StandardCharsets.UTF_8)).contains("\"code\":\"BANNED\"");
        }
    }

    @Test
    void aBanThatHasAlreadyEndedClosesNothing() {
        connected(user(StompPrincipal.user("g1", "p1", "kc-mallory", Set.of(), NOW.plusSeconds(60)), "ws-1"));

        enforcer.onUserBanned(new UserBannedEvent("kc-mallory", NOW.minusSeconds(1)));

        verify(outbound, never()).send(any());
    }

    @Test
    void aTimedBanStillInForceCloses() {
        connected(user(StompPrincipal.user("g1", "p1", "kc-mallory", Set.of(), NOW.plusSeconds(60)), "ws-1"));

        enforcer.onUserBanned(new UserBannedEvent("kc-mallory", NOW.plusSeconds(3600)));

        assertThat(sentFrames()).hasSize(1);
    }

    @Test
    void blankSubjectClosesNothing() {
        connected(user(StompPrincipal.guest("g1", "p3"), "ws-4"));
        assertThat(enforcer.closeSessionsOf(null)).isZero();
        assertThat(enforcer.closeSessionsOf(" ")).isZero();
        verify(outbound, never()).send(any());
    }
}
