package com.soulsoftworks.sockbowlgame.controller.exception;

import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;

/**
 * A WebSocket handler could not verify the sending player (the session or the
 * player is gone, or the connection carries no principal). Handled by
 * {@code StompExceptionAdvice}: a non-fatal {@code StompError} with
 * {@link #getCode()} goes to {@code /user/queue/errors} and the socket stays open.
 */
public class PlayerVerificationException extends RuntimeException {

    private final StompErrorCode code;

    public PlayerVerificationException(String message) {
        this(StompErrorCode.PLAYER_NOT_IN_SESSION, message);
    }

    public PlayerVerificationException(StompErrorCode code, String message) {
        super(message);
        this.code = code == null ? StompErrorCode.PLAYER_NOT_IN_SESSION : code;
    }

    public StompErrorCode getCode() {
        return code;
    }
}
