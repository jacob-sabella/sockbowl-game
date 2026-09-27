package com.soulsoftworks.sockbowlgame.security.stomp;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.ArrayList;
import java.util.List;

import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.frame;
import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.message;
import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.withUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guard pipeline contract M4 builds on (plan m2-auth section 2.5 and critic
 * note 1): pre-auth guards ({@code order < 0}) run before CONNECT
 * authentication and see no principal; {@code DROP} returns {@code null} and
 * stops the chain; a thrown {@link StompRejectedException} propagates.
 */
class StompInboundInterceptorTest {

    private static final StompPrincipal PRINCIPAL = StompPrincipal.guest("g1", "p1");

    private final MessageChannel channel = mock(MessageChannel.class);
    private final List<String> calls = new ArrayList<>();

    /** A test-only guard that records its call and the principal it saw. */
    private class RecordingGuard implements StompInboundGuard {
        private final String name;
        private final int order;
        private final StompGuardResult result;
        private final RuntimeException toThrow;
        final List<StompPrincipal> seen = new ArrayList<>();

        RecordingGuard(String name, int order, StompGuardResult result) {
            this(name, order, result, null);
        }

        RecordingGuard(String name, int order, StompGuardResult result, RuntimeException toThrow) {
            this.name = name;
            this.order = order;
            this.result = result;
            this.toThrow = toThrow;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public StompGuardResult check(StompHeaderAccessor accessor, StompPrincipal principal) {
            calls.add(name);
            seen.add(principal);
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    private StompConnectAuthenticator authenticator() {
        StompConnectAuthenticator authenticator = mock(StompConnectAuthenticator.class);
        when(authenticator.authenticate(any())).thenAnswer(inv -> {
            calls.add("authenticate");
            return PRINCIPAL;
        });
        return authenticator;
    }

    @Test
    void preAuthGuardRunsBeforeAuthenticatorOnConnectWithNullPrincipal() {
        RecordingGuard rateLimit = new RecordingGuard("rateLimit(-100)", -100, StompGuardResult.PASS);
        RecordingGuard destination = new RecordingGuard("destination(100)", 100, StompGuardResult.PASS);
        RecordingGuard usage = new RecordingGuard("usage(300)", 300, StompGuardResult.PASS);
        // Registered out of order on purpose: the interceptor sorts by order().
        StompInboundInterceptor interceptor =
                new StompInboundInterceptor(authenticator(), List.of(usage, destination, rateLimit));

        StompHeaderAccessor connect = frame(StompCommand.CONNECT, null);
        Message<byte[]> msg = message(connect);
        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        assertThat(calls).containsExactly("rateLimit(-100)", "authenticate", "destination(100)", "usage(300)");
        assertThat(rateLimit.seen).containsExactly((StompPrincipal) null);
        assertThat(destination.seen).containsExactly(PRINCIPAL);
        // The principal is bound to the connection via setUser.
        StompHeaderAccessor after = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(after.getUser()).isSameAs(PRINCIPAL);
    }

    @Test
    void onOtherFramesGuardsReceiveTheBoundPrincipalAndAuthenticatorIsNotCalled() {
        RecordingGuard pre = new RecordingGuard("pre", -100, StompGuardResult.PASS);
        RecordingGuard post = new RecordingGuard("post", 100, StompGuardResult.PASS);
        StompConnectAuthenticator authenticator = authenticator();
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator, List.of(post, pre));

        Message<byte[]> msg = message(withUser(frame(StompCommand.SEND, "/app/game/x"), PRINCIPAL));
        assertThat(interceptor.preSend(msg, channel)).isSameAs(msg);

        assertThat(calls).containsExactly("pre", "post");
        assertThat(pre.seen).containsExactly(PRINCIPAL);
        assertThat(post.seen).containsExactly(PRINCIPAL);
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void preAuthDropOnConnectSkipsAuthenticationAndLaterGuards() {
        RecordingGuard drop = new RecordingGuard("drop", -100, StompGuardResult.DROP);
        RecordingGuard post = new RecordingGuard("post", 100, StompGuardResult.PASS);
        StompConnectAuthenticator authenticator = authenticator();
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator, List.of(drop, post));

        assertThat(interceptor.preSend(message(frame(StompCommand.CONNECT, null)), channel)).isNull();
        assertThat(calls).containsExactly("drop");
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void postAuthDropReturnsNullAndLaterGuardsDoNotRun() {
        RecordingGuard first = new RecordingGuard("first", 100, StompGuardResult.PASS);
        RecordingGuard drop = new RecordingGuard("drop", 200, StompGuardResult.DROP);
        RecordingGuard last = new RecordingGuard("last", 300, StompGuardResult.PASS);
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator(), List.of(last, drop, first));

        Message<?> result = interceptor.preSend(
                message(withUser(frame(StompCommand.SEND, "/app/game/buzz"), PRINCIPAL)), channel);

        assertThat(result).isNull();
        assertThat(calls).containsExactly("first", "drop");
        assertThat(last.seen).isEmpty();
    }

    @Test
    void throwingGuardPropagatesRejectionAndStopsTheChain() {
        StompRejectedException rejection = new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION, "nope");
        RecordingGuard thrower = new RecordingGuard("thrower", 100, StompGuardResult.PASS, rejection);
        RecordingGuard last = new RecordingGuard("last", 300, StompGuardResult.PASS);
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator(), List.of(thrower, last));

        assertThatThrownBy(() -> interceptor.preSend(
                message(withUser(frame(StompCommand.SEND, "/queue/x"), PRINCIPAL)), channel))
                .isSameAs(rejection);
        assertThat(calls).containsExactly("thrower");
    }

    @Test
    void preAuthThrowOnConnectPreventsAuthentication() {
        StompRejectedException rejection = new StompRejectedException(StompErrorCode.INTERNAL, "flood");
        RecordingGuard thrower = new RecordingGuard("thrower", -100, StompGuardResult.PASS, rejection);
        StompConnectAuthenticator authenticator = authenticator();
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator, List.of(thrower));

        assertThatThrownBy(() -> interceptor.preSend(message(frame(StompCommand.CONNECT, null)), channel))
                .isSameAs(rejection);
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void authenticatorRejectionPropagatesAndPostAuthGuardsDoNotRun() {
        StompConnectAuthenticator authenticator = mock(StompConnectAuthenticator.class);
        when(authenticator.authenticate(any()))
                .thenThrow(new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "bad"));
        RecordingGuard post = new RecordingGuard("post", 100, StompGuardResult.PASS);
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator, List.of(post));

        assertThatThrownBy(() -> interceptor.preSend(message(frame(StompCommand.CONNECT, null)), channel))
                .isInstanceOf(StompRejectedException.class)
                .extracting(e -> ((StompRejectedException) e).getCode())
                .isEqualTo(StompErrorCode.INVALID_CREDENTIALS);
        assertThat(post.seen).isEmpty();
    }

    @Test
    void aNonStompPrincipalIsTreatedAsNoPrincipal() {
        RecordingGuard post = new RecordingGuard("post", 100, StompGuardResult.PASS);
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator(), List.of(post));

        interceptor.preSend(message(withUser(frame(StompCommand.SEND, "/app/x"), () -> "http-user")), channel);
        assertThat(post.seen).containsExactly((StompPrincipal) null);
    }

    @Test
    void realDestinationGuardIsPostAuth() {
        StompInboundInterceptor interceptor = new StompInboundInterceptor(authenticator(),
                List.of(new StompDestinationGuard(null, null), new RecordingGuard("pre", -100, StompGuardResult.PASS)));
        assertThat(interceptor.guardsInOrder()).extracting(StompInboundGuard::order).containsExactly(-100, 100);
    }
}
