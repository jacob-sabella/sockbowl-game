package com.soulsoftworks.sockbowlgame.websocket.controller;

import com.soulsoftworks.sockbowlgame.websocket.WebSocketConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeartbeatControllerTest {

    @Value("${local.server.port}")
    private int port;

    private static String HEARTBEAT_TOPIC = "/topic/heartbeat";
    private static String HEARTBEAT_APP = "/app/heartbeat";

    // Used for async
    private CompletableFuture<String> completableFuture;

    @BeforeEach
    void setup(){
        completableFuture = new CompletableFuture<>();
    }

    @Test
    void heartbeat_returnsExpectedValue() throws ExecutionException, InterruptedException, TimeoutException {
        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new StringMessageConverter());
        String url =  "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;
        StompSession stompSession = stompClient.connect(url, new StompSessionHandlerAdapter(){})
                .get(1, SECONDS);

        // Subscribe to response endpoint
        stompSession.subscribe(HEARTBEAT_TOPIC, new MySimpleStompFrameHandler());

        // Send to app endpoint
        stompSession.send(HEARTBEAT_APP, null);

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Assert that response is as we expected
        assertEquals("Hello! I am alive!", response);
    }

    private List<Transport> createTransportClient() {
        List<Transport> transports = new ArrayList<>(1);
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        return transports;
    }

    public class MySimpleStompFrameHandler extends StompSessionHandlerAdapter implements StompFrameHandler {

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