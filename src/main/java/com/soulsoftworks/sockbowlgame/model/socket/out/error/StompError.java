package com.soulsoftworks.sockbowlgame.model.socket.out.error;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A non-fatal STOMP error delivered to the offending connection on
 * {@code /user/queue/errors} (plan m2-auth section 2.5). The socket stays open.
 *
 * <p>{@code code} is a {@code StompErrorCode} name (UPPER_SNAKE). This is a
 * frozen contract: M4 extends this class with <b>optional</b> fields
 * ({@code policy}, {@code retryAfterMs}, {@code droppedDestination}) rather than
 * adding another error message type; clients must ignore unknown fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StompError {

    public static final String MESSAGE_TYPE = "StompError";

    /** Always {@value #MESSAGE_TYPE}. */
    @Builder.Default
    private String messageType = MESSAGE_TYPE;

    /** Same tag every other server message carries, so generic dispatch works. */
    @Builder.Default
    private String messageContentType = MESSAGE_TYPE;

    private String code;
    private String message;
    /** Null in M2; M4 fills it for throttling. */
    private Integer retryAfterSeconds;

    /** M4 (optional): the same wait in milliseconds, for client-side lockouts (e.g. the buzzer). */
    private Long retryAfterMs;

    /**
     * M4 (optional): the limiter policy that dropped the frame ({@code stomp-send},
     * {@code stomp-send-ip}, {@code stomp-buzz}); for {@code QUOTA_EXCEEDED} the
     * quota metric (e.g. {@code hosted-sessions}).
     */
    private String policy;

    /** M4 (optional): the SEND destination of the dropped frame. */
    private String droppedDestination;

    public static StompError of(String code, String message, Integer retryAfterSeconds) {
        return StompError.builder().code(code).message(message).retryAfterSeconds(retryAfterSeconds).build();
    }
}
