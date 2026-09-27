package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.nio.charset.StandardCharsets;

import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.frame;
import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.message;
import static org.assertj.core.api.Assertions.assertThat;

/** ERROR frame wire format (plan m2-auth section 2.5, AUTH-16). */
class SockbowlStompErrorHandlerTest {

    private final SockbowlStompErrorHandler handler = new SockbowlStompErrorHandler();

    private static StompHeaderAccessor headers(Message<byte[]> error) {
        return MessageHeaderAccessor.getAccessor(error, StompHeaderAccessor.class);
    }

    private static JsonObject body(Message<byte[]> error) {
        return JsonParser.parseString(new String(error.getPayload(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void rejectionBecomesTypedErrorFrame() {
        Message<byte[]> client = message(frame(StompCommand.CONNECT, null));
        Message<byte[]> error = handler.handleClientMessageProcessingError(client,
                new StompRejectedException(StompErrorCode.INVALID_CREDENTIALS, "Invalid player credentials"));

        StompHeaderAccessor h = headers(error);
        assertThat(h.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(h.getMessage()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(h.getFirstNativeHeader("x-sockbowl-error")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(h.getContentType().toString()).isEqualTo("application/json");

        JsonObject body = body(error);
        assertThat(body.keySet()).containsExactly("code", "message", "retryAfterSeconds");
        assertThat(body.get("code").getAsString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(body.get("message").getAsString()).isEqualTo("Invalid player credentials");
        assertThat(body.get("retryAfterSeconds").isJsonNull()).isTrue();
    }

    @Test
    void rejectionIsFoundInsideTheChannelsWrapper() {
        Message<byte[]> client = message(frame(StompCommand.SEND, "/queue/event/g1"));
        StompRejectedException cause = new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION, "no");
        Message<byte[]> error = handler.handleClientMessageProcessingError(client,
                new MessageDeliveryException(client, "Failed to send message", cause));

        assertThat(headers(error).getMessage()).isEqualTo("FORBIDDEN_DESTINATION");
        assertThat(body(error).get("code").getAsString()).isEqualTo("FORBIDDEN_DESTINATION");
    }

    @Test
    void retryAfterIsCarriedWhenSet() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(message(frame(StompCommand.SEND, "/app/x")),
                new StompRejectedException(StompErrorCode.INTERNAL, "later", 7));
        assertThat(body(error).get("retryAfterSeconds").getAsInt()).isEqualTo(7);
    }

    @Test
    void rateLimitedCarriesRetryAfterAndPolicy() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(message(frame(StompCommand.SEND, "/app/x")),
                new StompRejectedException(StompErrorCode.RATE_LIMITED, "flood", 10, "stomp-flood"));
        assertThat(headers(error).getMessage()).isEqualTo("RATE_LIMITED");
        assertThat(headers(error).getFirstNativeHeader("x-sockbowl-error")).isEqualTo("RATE_LIMITED");
        JsonObject body = body(error);
        assertThat(body.get("code").getAsString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("retryAfterSeconds").getAsInt()).isEqualTo(10);
        assertThat(body.get("policy").getAsString()).isEqualTo("stomp-flood");
    }

    @Test
    void policyIsOmittedWhenThereIsNone() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(message(frame(StompCommand.CONNECT, null)),
                new StompRejectedException(StompErrorCode.IP_BANNED, "banned", 60));
        assertThat(body(error).has("policy")).isFalse();
        assertThat(body(error).get("code").getAsString()).isEqualTo("IP_BANNED");
    }

    @Test
    void unknownExceptionIsInternalWithoutLeakingDetails() {
        RuntimeException secret = new IllegalStateException("db password=hunter2 at com.example.Foo");
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                message(frame(StompCommand.SEND, "/app/x")), new MessageDeliveryException(message(frame(StompCommand.SEND, "/app/x")), "wrapped", secret));

        assertThat(headers(error).getMessage()).isEqualTo("INTERNAL");
        assertThat(headers(error).getFirstNativeHeader("x-sockbowl-error")).isEqualTo("INTERNAL");
        String raw = new String(error.getPayload(), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("hunter2", "Exception", "com.example", "\tat ", "wrapped");
        assertThat(body(error).get("code").getAsString()).isEqualTo("INTERNAL");
    }

    @Test
    void receiptIsEchoed() {
        StompHeaderAccessor send = frame(StompCommand.SEND, "/queue/x");
        send.setReceipt("r-42");
        Message<byte[]> error = handler.handleClientMessageProcessingError(message(send),
                new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION, "no"));
        assertThat(headers(error).getReceiptId()).isEqualTo("r-42");
    }

    @Test
    void nullClientMessageIsHandled() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(null,
                new StompRejectedException(StompErrorCode.AUTH_REQUIRED, null));
        assertThat(headers(error).getMessage()).isEqualTo("AUTH_REQUIRED");
        assertThat(body(error).get("message").getAsString()).isEqualTo("AUTH_REQUIRED");
    }
}
