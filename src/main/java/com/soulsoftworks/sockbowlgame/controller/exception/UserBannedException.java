package com.soulsoftworks.sockbowlgame.controller.exception;

/**
 * Raised when a banned user attempts an action that is denied because of an
 * active ban (joining a game, creating a game, or any configuration action).
 */
public class UserBannedException extends RuntimeException {

    public UserBannedException(String message) {
        super(message);
    }
}
