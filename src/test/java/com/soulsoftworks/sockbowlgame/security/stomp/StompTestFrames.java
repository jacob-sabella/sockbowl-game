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

    static StompHeaderAccessor frame(StompCommand command, String destination, String... nativeHeaders) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("ws-1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        for (int i = 0; i + 1 < nativeHeaders.length; i += 2) {
            accessor.setNativeHeader(nativeHeaders[i], nativeHeaders[i + 1]);
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
