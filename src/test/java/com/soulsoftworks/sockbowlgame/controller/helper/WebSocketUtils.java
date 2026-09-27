package com.soulsoftworks.sockbowlgame.controller.helper;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WebSocketUtils {

    public static  List<Transport> createTransportClient() {
        List<Transport> transports = new ArrayList<>(1);
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        return transports;
    }

    /**
     * CONNECT headers for a guest player seat. Since M2 WP-G2 the server
     * authenticates every STOMP connection at CONNECT, so a client must send
     * these (or {@code Authorization: Bearer} for a signed-in seat) before any
     * SUBSCRIBE or SEND.
     */
    public static StompHeaders connectHeaders(String gameSessionId, String playerSessionId, String playerSecret) {
        return StompTestClient.connectHeaders(gameSessionId, playerSessionId, playerSecret, null);
    }

    public static class SimpleStompFrameHandler extends StompSessionHandlerAdapter implements StompFrameHandler {
        CompletableFuture completableFuture;

        public SimpleStompFrameHandler(CompletableFuture completableFuture) {
            this.completableFuture = completableFuture;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            String msg = (String) payload;
            completableFuture.complete(msg);
        }
    }
}
