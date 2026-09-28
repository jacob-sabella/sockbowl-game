package com.soulsoftworks.sockbowlgame.security.stomp;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

/** Builds inbound frames the way Spring's STOMP decoder hands them to the channel. */
final class StompTestFrames {

    private StompTestFrames() {
    }

    /**
     * A {@code nativeHeaders} pair of {@code "id", "<value>"} sets the
     * SUBSCRIBE/UNSUBSCRIBE {@code id} header explicitly (used to give
     * distinct subscriptions on the same connection distinct ids, or to
     * unsubscribe a specific one); without it, a SUBSCRIBE defaults to
     * {@code "sub-0"} and an UNSUBSCRIBE carries no id at all, matching a
     * real STOMP frame missing that header.
     */
    static StompHeaderAccessor frame(StompCommand command, String destination, String... nativeHeaders) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("ws-1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        String id = null;
        for (int i = 0; i + 1 < nativeHeaders.length; i += 2) {
            if ("id".equals(nativeHeaders[i])) {
                id = nativeHeaders[i + 1];
            } else {
                accessor.setNativeHeader(nativeHeaders[i], nativeHeaders[i + 1]);
            }
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId(id != null ? id : "sub-0");
        } else if (command == StompCommand.UNSUBSCRIBE && id != null) {
            accessor.setSubscriptionId(id);
        }
        accessor.setLeaveMutable(true);
        return accessor;
    }

    static StompHeaderAccessor withUser(StompHeaderAccessor accessor, Principal user) {
        accessor.setUser(user);
        return accessor;
    }

    static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
