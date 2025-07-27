package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static com.soulsoftworks.sockbowlgame.controller.helper.WebSocketUtils.createTransportClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeartbeatControllerTest {

    @Value("${local.server.port}")
    private int port;

    private static String HEARTBEAT_TOPIC = "/queue/heartbeat";
    private static String HEARTBEAT_APP = "/app/heartbeat";

    // Used for async
    private CompletableFuture<String> completableFuture;

    @BeforeEach
    void setup(){
        completableFuture = new CompletableFuture<>();
    }

    @Test
    @Disabled
    void heartbeat_returnsExpectedValue() throws ExecutionException, InterruptedException, TimeoutException {
        // Setup web socket client
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(createTransportClient()));
        stompClient.setMessageConverter(new StringMessageConverter());
        String url =  "ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT;
        StompSession stompSession = stompClient.connect(url, new StompSessionHandlerAdapter(){})
                .get(1, SECONDS);

        // Subscribe to response endpoint
        stompSession.subscribe(HEARTBEAT_TOPIC, new WebSocketUtils.SimpleStompFrameHandler(completableFuture));

        // Send to app endpoint
        stompSession.send(HEARTBEAT_APP, null);

        // Wait for value
        String response = completableFuture.get(10, SECONDS);

        // Assert that response is as we expected
        assertEquals("Hello! I am alive!", response);
    }
}