package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.controller.exception.PlayerVerificationException;
import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.StompError;
import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;
import com.soulsoftworks.sockbowlgame.security.stomp.StompRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * The channel for <b>non-fatal</b> STOMP errors (plan m2-auth section 2.5): an
 * exception thrown while handling a {@code @MessageMapping} (including argument
 * resolution) becomes a {@link StompError} sent only to the offending
 * connection on {@code /user/queue/errors}, and the socket stays open. Fatal
 * rejections are ERROR frames from the inbound interceptor instead.
 *
 * <p>Frozen contract: M4 <b>extends this class</b> (more handlers, e.g. for
 * throttled buzzes) rather than adding a second advice.
 */
@ControllerAdvice
public class StompExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(StompExceptionAdvice.class);

    static final String ERRORS_DESTINATION = "/queue/errors";

    @MessageExceptionHandler(StompRejectedException.class)
    @SendToUser(destinations = ERRORS_DESTINATION, broadcast = false)
    public StompError handleRejected(StompRejectedException ex) {
        return StompError.of(ex.getCode().name(), ex.getDetail(), ex.getRetryAfterSeconds());
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
}
