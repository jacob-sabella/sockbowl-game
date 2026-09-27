package com.soulsoftworks.sockbowlgame.security.stomp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Comparator;
import java.util.List;

/**
 * The single inbound STOMP interceptor (plan m2-auth section 2.5; M4 adds guards,
 * it does not register a second interceptor). Per frame:
 * <ol>
 *   <li>run the pre-auth guards ({@link StompInboundGuard#order()} {@code < 0});
 *       any {@code DROP} returns {@code null} (frame dropped, socket open);</li>
 *   <li>on CONNECT, authenticate with {@link StompConnectAuthenticator} and bind
 *       the resulting {@link StompPrincipal} with {@code accessor.setUser};</li>
 *   <li>run the post-auth guards ({@code order() >= 0}) in order; {@code DROP}
 *       returns {@code null} and no later guard runs.</li>
 * </ol>
 * A {@link StompRejectedException} from any step propagates; Spring's STOMP
 * handler then sends the ERROR frame built by {@link SockbowlStompErrorHandler}
 * and closes the socket. Applies in both auth modes.
 *
 * <p>The client IP, once M4's handshake interceptor stores it, is available to
 * guards as {@code accessor.getSessionAttributes().get("sockbowl.clientIp")}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompInboundInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompInboundInterceptor.class);

    private final StompConnectAuthenticator authenticator;
    private final List<StompInboundGuard> preAuthGuards;
    private final List<StompInboundGuard> postAuthGuards;

    public StompInboundInterceptor(StompConnectAuthenticator authenticator, List<StompInboundGuard> guards) {
        this.authenticator = authenticator;
        List<StompInboundGuard> sorted = guards.stream()
                .sorted(Comparator.comparingInt(StompInboundGuard::order))
                .toList();
        this.preAuthGuards = sorted.stream().filter(g -> g.order() < 0).toList();
        this.postAuthGuards = sorted.stream().filter(g -> g.order() >= 0).toList();
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            // Not a client STOMP frame (e.g. an internally built message): nothing to check.
            return message;
        }
        boolean connect = StompDestinationGuard.isConnect(accessor);
        StompPrincipal principal = connect ? null : principalOf(accessor.getUser());

        if (!runGuards(preAuthGuards, accessor, principal)) {
            return null;
        }

        if (connect) {
            principal = authenticator.authenticate(accessor);
            accessor.setUser(principal);
            log.debug("STOMP CONNECT authenticated {}", principal);
        }

        if (!runGuards(postAuthGuards, accessor, principal)) {
            return null;
        }
        return message;
    }

    /** @return false when a guard dropped the frame */
    private static boolean runGuards(List<StompInboundGuard> guards, StompHeaderAccessor accessor,
                                     StompPrincipal principal) {
        for (StompInboundGuard guard : guards) {
            if (guard.check(accessor, principal) == StompGuardResult.DROP) {
                log.debug("STOMP {} to {} dropped by {}", accessor.getCommand(), accessor.getDestination(),
                        guard.getClass().getSimpleName());
                return false;
            }
        }
        return true;
    }

    private static StompPrincipal principalOf(Principal user) {
        return user instanceof StompPrincipal stompPrincipal ? stompPrincipal : null;
    }

    /** Guards in execution order (pre-auth first); for tests and diagnostics. */
    List<StompInboundGuard> guardsInOrder() {
        return java.util.stream.Stream.concat(preAuthGuards.stream(), postAuthGuards.stream()).toList();
    }
}
