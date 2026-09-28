package com.soulsoftworks.sockbowlgame.controller.helper;

import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A minimal real STOMP-over-WebSocket client for integration tests: connects
 * with arbitrary CONNECT headers, exposes the ERROR frame (if any), whether the
 * server closed the socket, and per-subscription queues of raw JSON bodies.
 */
public class StompTestClient {

    private final String url;
    private final WebSocketStompClient client;

    public StompTestClient(int port) {
        this.url = "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;
        this.client = new WebSocketStompClient(new StandardWebSocketClient());
        this.client.setMessageConverter(new RawJsonConverter());
    }

    /** CONNECT headers for a player seat (null values are omitted). */
    public static StompHeaders connectHeaders(String gameSessionId, String playerSessionId,
                                              String playerSecret, String bearerToken) {
        StompHeaders headers = new StompHeaders();
        if (gameSessionId != null) {
            headers.add("gameSessionId", gameSessionId);
        }
        if (playerSessionId != null) {
            headers.add("playerSessionId", playerSessionId);
        }
        if (playerSecret != null) {
            headers.add("playerSecret", playerSecret);
        }
        if (bearerToken != null) {
            headers.add("Authorization", "Bearer " + bearerToken);
        }
        return headers;
    }

    /** Starts a connection; use {@link Connection#awaitConnected()} or {@link Connection#awaitError()}. */
    public Connection connect(StompHeaders connectHeaders) {
        Connection connection = new Connection();
        client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders, connection);
        return connection;
    }

    public void stop() {
        client.stop();
    }

    public record ErrorFrame(String message, String code, String body) {
    }

    public static final class Connection extends StompSessionHandlerAdapter {
        private final CompletableFuture<StompSession> connected = new CompletableFuture<>();
        private final CompletableFuture<ErrorFrame> error = new CompletableFuture<>();
        private final CompletableFuture<Throwable> closed = new CompletableFuture<>();
        private volatile StompSession session;

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            this.session = session;
            connected.complete(session);
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        /** Only ERROR frames reach the session handler. */
        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            error.complete(new ErrorFrame(headers.getFirst("message"), headers.getFirst("x-sockbowl-error"),
                    (String) payload));
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            closed.complete(exception);
            connected.completeExceptionally(exception);
        }

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            error.completeExceptionally(exception);
        }

        public StompSession awaitConnected() throws Exception {
            return connected.get(10, TimeUnit.SECONDS);
        }

        public ErrorFrame awaitError() throws Exception {
            return error.get(10, TimeUnit.SECONDS);
        }

        /** True once the server has closed the socket (it does so after every ERROR frame). */
        public boolean awaitClosed() {
            try {
                closed.get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public boolean isConnected() {
            return session != null && session.isConnected() && !closed.isDone();
        }

        public BlockingQueue<String> subscribe(String destination) {
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            subscribeForHandle(destination, queue);
            return queue;
        }

        /**
         * Like {@link #subscribe}, but also returns the
         * {@link StompSession.Subscription} handle, so a caller can send a real
         * UNSUBSCRIBE (including a second, now-stale one) with
         * {@code Subscription#unsubscribe()}.
         */
        public StompSession.Subscription subscribeForHandle(String destination, BlockingQueue<String> queue) {
            return session.subscribe(destination, new StompSessionHandlerAdapter() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add((String) payload);
                }
            });
        }

        public void send(String destination, String json, String... extraHeaders) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination(destination);
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                headers.add(extraHeaders[i], extraHeaders[i + 1]);
            }
            session.send(headers, json);
        }

        public void disconnect() {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /** Frames travel as raw JSON strings in both directions. */
    static final class RawJsonConverter extends AbstractMessageConverter {
        RawJsonConverter() {
            super(new MimeType[]{MimeTypeUtils.APPLICATION_JSON, MimeTypeUtils.TEXT_PLAIN});
            setStrictContentTypeMatch(false);
        }

        @Override
        protected boolean supports(Class<?> clazz) {
            return String.class == clazz;
        }

        @Override
        protected Object convertFromInternal(Message<?> message, Class<?> targetClass, Object conversionHint) {
            Object payload = message.getPayload();
            return payload instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : payload.toString();
        }

        @Override
        protected Object convertToInternal(Object payload, MessageHeaders headers, Object conversionHint) {
            return payload.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
