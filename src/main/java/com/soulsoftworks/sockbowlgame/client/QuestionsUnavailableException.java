package com.soulsoftworks.sockbowlgame.client;

/**
 * sockbowl-questions could not be reached, or would not answer for this
 * service (AUTH-18). Callers turn this into a {@code PACKET_SERVICE_UNAVAILABLE}
 * error for the player instead of letting it escape a Kafka listener.
 *
 * <p>The message never contains a token or the client secret.
 */
public class QuestionsUnavailableException extends RuntimeException {

    /** Why the call failed. */
    public enum Reason {
        /** No service token could be obtained from Keycloak (down, wrong secret, misconfigured client). */
        TOKEN,
        /** questions rejected the service token (HTTP 401/403, or an UNAUTHORIZED/FORBIDDEN GraphQL error). */
        AUTH,
        /** questions did not answer within {@code sockbowl.questions.timeout}. */
        TIMEOUT,
        /** questions was unreachable or answered with a server error. */
        UNAVAILABLE,
        /** questions answered, but with GraphQL errors or a body the game could not read. */
        BAD_RESPONSE
    }

    private final Reason reason;

    public QuestionsUnavailableException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public QuestionsUnavailableException(Reason reason, String detail, Throwable cause) {
        super(reason + ": " + detail, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
