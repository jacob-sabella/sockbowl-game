package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.service.UserBannedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Closes the STOMP connections of a user who has just been banned (G-04).
 *
 * <p>Bans are checked at CONNECT, on SUBSCRIBE and on every game SEND, but a
 * subscription that already exists keeps receiving broker events without any
 * frame from the client. So when a ban is created every connection whose
 * {@link StompPrincipal} carries the banned subject is sent a typed
 * {@code BANNED} ERROR frame (same wire format as
 * {@link SockbowlStompErrorHandler}); Spring closes the socket after an ERROR
 * frame. ng treats {@code BANNED} as fatal and does not reconnect.
 */
@Component
public class StompBanEnforcer {

    private static final Logger log = LoggerFactory.getLogger(StompBanEnforcer.class);

    static final String BANNED_DETAIL = "Your account is banned and cannot participate in games.";

    private final SimpUserRegistry userRegistry;
    private final MessageChannel clientOutboundChannel;
    private final Clock clock;

    @Autowired
    public StompBanEnforcer(SimpUserRegistry userRegistry,
                            @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this(userRegistry, clientOutboundChannel, Clock.systemUTC());
    }

    StompBanEnforcer(SimpUserRegistry userRegistry, MessageChannel clientOutboundChannel, Clock clock) {
        this.userRegistry = userRegistry;
        this.clientOutboundChannel = clientOutboundChannel;
        this.clock = clock;
    }

    @EventListener
    public void onUserBanned(UserBannedEvent event) {
        if (event.expiresAt() != null && !event.expiresAt().isAfter(clock.instant())) {
            return; // already over
        }
        int closed = closeSessionsOf(event.bannedKeycloakId());
        if (closed > 0) {
            log.info("Closed {} STOMP session(s) of a banned user", closed);
        }
    }

    /**
     * Send a {@code BANNED} ERROR frame to every STOMP session of the given
     * Keycloak subject, which closes each socket.
     *
     * @return how many sessions were sent the frame
     */
    public int closeSessionsOf(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) {
            return 0;
        }
        List<String> sessionIds = new ArrayList<>();
        for (SimpUser user : userRegistry.getUsers()) {
            if (user.getPrincipal() instanceof StompPrincipal principal
                    && !principal.isGuest()
                    && keycloakId.equals(principal.getKeycloakId())) {
                for (SimpSession session : user.getSessions()) {
                    sessionIds.add(session.getId());
                }
            }
        }
        for (String sessionId : sessionIds) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
            accessor.setSessionId(sessionId);
            accessor.setMessage(StompErrorCode.BANNED.name());
            accessor.setNativeHeader(SockbowlStompErrorHandler.ERROR_CODE_HEADER, StompErrorCode.BANNED.name());
            accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
            accessor.setLeaveMutable(true);
            clientOutboundChannel.send(MessageBuilder.createMessage(
                    SockbowlStompErrorHandler.body(StompErrorCode.BANNED, BANNED_DETAIL, null),
                    accessor.getMessageHeaders()));
        }
        return sessionIds.size();
    }
}
