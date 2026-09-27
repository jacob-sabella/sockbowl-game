package com.soulsoftworks.sockbowlgame.security.stomp;

import org.springframework.messaging.MessagingException;

/**
 * Raised by the STOMP inbound pipeline (a {@link StompInboundGuard}, the
 * {@link StompConnectAuthenticator}) or by a handler to reject a frame with a
 * typed {@link StompErrorCode}.
 *
 * <p>Thrown from the inbound interceptor it is <b>fatal</b>:
 * {@link SockbowlStompErrorHandler} turns it into an ERROR frame and the server
 * closes the socket. Thrown from a {@code @MessageMapping} handler (or its
 * argument resolver) it is <b>non-fatal</b>: {@code StompExceptionAdvice} sends a
 * {@code StompError} to {@code /user/queue/errors} and the socket stays open.
 */
public class StompRejectedException extends MessagingException {

    private final StompErrorCode code;
    private final String detail;
    /** Null in M2; M4 fills it for {@code RATE_LIMITED}. */
    private final Integer retryAfterSeconds;
    /** M4: the limiter policy that rejected the frame (e.g. {@code stomp-flood}); null otherwise. */
    private final String policy;

    public StompRejectedException(StompErrorCode code, String detail) {
        this(code, detail, null);
    }

    public StompRejectedException(StompErrorCode code, String detail, Integer retryAfterSeconds) {
        this(code, detail, retryAfterSeconds, null);
    }

    /** M4: a limiter rejection that names its {@code policy} (optional ERROR-body field). */
    public StompRejectedException(StompErrorCode code, String detail, Integer retryAfterSeconds, String policy) {
        super(code.name() + (detail == null ? "" : ": " + detail));
        this.code = code;
        this.detail = detail;
        this.retryAfterSeconds = retryAfterSeconds;
        this.policy = policy;
    }

    public StompErrorCode getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getPolicy() {
        return policy;
    }
}
