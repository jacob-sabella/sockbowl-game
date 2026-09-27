package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.controller.exception.PlayerVerificationException;
import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.StompError;
import com.soulsoftworks.sockbowlgame.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;
import com.soulsoftworks.sockbowlgame.security.stomp.StompRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.time.Clock;
import java.time.Duration;

/**
 * The channel for <b>non-fatal</b> STOMP errors (plan m2-auth section 2.5): an
 * exception thrown while handling a {@code @MessageMapping} (including argument
 * resolution) becomes a {@link StompError} sent only to the offending
 * connection on {@code /user/queue/errors}, and the socket stays open. Fatal
 * rejections are ERROR frames from the inbound interceptor instead.
 *
 * <p>Frozen contract: M4 <b>extends this class</b> (more handlers, e.g. for
 * throttled buzzes) rather than adding a second advice. M4 adds
 * {@link QuotaExceededException} ({@code QUOTA_EXCEEDED}) and
 * {@link IllegalArgumentException} (a {@link ProcessError}), which were
 * previously swallowed by Spring and only logged.
 */
@ControllerAdvice
public class StompExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(StompExceptionAdvice.class);

    static final String ERRORS_DESTINATION = "/queue/errors";

    private final Clock clock;

    public StompExceptionAdvice(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @MessageExceptionHandler(StompRejectedException.class)
    @SendToUser(destinations = ERRORS_DESTINATION, broadcast = false)
    public StompError handleRejected(StompRejectedException ex) {
        StompError error = StompError.of(ex.getCode().name(), ex.getDetail(), ex.getRetryAfterSeconds());
        error.setPolicy(ex.getPolicy());
        if (ex.getRetryAfterSeconds() != null) {
            error.setRetryAfterMs(ex.getRetryAfterSeconds() * 1000L);
        }
        return error;
    }

    @MessageExceptionHandler(PlayerVerificationException.class)
    @SendToUser(destinations = ERRORS_DESTINATION, broadcast = false)
    public StompError handlePlayerVerification(PlayerVerificationException ex) {
        log.info("STOMP handler rejected a frame: {} ({})", ex.getCode(), ex.getMessage());
        return StompError.of(ex.getCode().name(), ex.getMessage(), null);
    }

    @MessageExceptionHandler(UserBannedException.class)
    @SendToUser(destinations = ERRORS_DESTINATION, broadcast = false)
    public StompError handleBanned(UserBannedException ex) {
        return StompError.of(StompErrorCode.BANNED.name(), ex.getMessage(), null);
    }

    /**
     * A quota ran out while handling the frame (M4). {@code policy} carries the
     * metric; {@code retryAfterSeconds} is set only for daily metrics.
     */
    @MessageExceptionHandler(QuotaExceededException.class)
    @SendToUser(destinations = ERRORS_DESTINATION, broadcast = false)
    public StompError handleQuotaExceeded(QuotaExceededException ex) {
        long retryAfter = ex.getResetsAt() == null ? 0
                : Math.max(1, Duration.between(clock.instant(), ex.getResetsAt()).toSeconds());
        return StompError.builder()
                .code(StompErrorCode.QUOTA_EXCEEDED.name())
                .message(ex.getMessage())
                .policy(ex.getMetric())
                .retryAfterSeconds(retryAfter > 0 ? (int) Math.min(Integer.MAX_VALUE, retryAfter) : null)
                .retryAfterMs(retryAfter > 0 ? retryAfter * 1000L : null)
                .build();
    }

    /**
     * A malformed or out-of-range argument in a handler (M4). Reported to the
     * sender as a {@link ProcessError} with a generic text: the exception message
     * may carry internals, so it is only logged.
     */
    @MessageExceptionHandler(IllegalArgumentException.class)
    @SendToUser(destinations = ERRORS_DESTINATION, broadcast = false)
    public ProcessError handleIllegalArgument(IllegalArgumentException ex) {
        log.info("STOMP handler rejected an invalid argument: {}", ex.getMessage());
        return ProcessError.builder()
                .code(INVALID_REQUEST)
                .error("Invalid request")
                .build();
    }

    static final String INVALID_REQUEST = "INVALID_REQUEST";
}
