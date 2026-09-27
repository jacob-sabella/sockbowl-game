package com.soulsoftworks.sockbowlgame.model.socket.in;

import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

@Data
@SuperBuilder
@NoArgsConstructor
public abstract class SockbowlInMessage {

    private String originatingPlayerId;
    private String gameSessionId;
    private GameSession gameSession;

    /**
     * Keycloak subject of the sender ({@code null} for guests). Serialized to
     * Kafka so consumers (e.g. the SetMatchPacket draft check) can see who sent
     * the message. Set <b>only</b> by {@link #stampOrigin(GameSessionInjection)};
     * whatever a client puts here in a STOMP body is overwritten.
     */
    private String originatingKeycloakId;

    /**
     * Authority names of the sender (empty for guests). Same protection as
     * {@link #originatingKeycloakId}.
     */
    @Builder.Default
    private Set<String> originatingAuthorities = new HashSet<>();

    public abstract MessageTypes getMessageType();

    /**
     * Unconditionally overwrite every identity field of this message from the
     * connection's authenticated principal, discarding anything the client put
     * in the body: the sender's player and game ids, its Keycloak subject and
     * authorities (null / empty for guests), and any client-supplied
     * {@code gameSession} (the Kafka consumer loads the real one). Every
     * {@code @MessageMapping} calls this before producing to Kafka; nothing else
     * sets these fields (plan m2-auth WP-G2, critic note 5).
     */
    public void stampOrigin(GameSessionInjection injection) {
        this.originatingPlayerId = injection.getPlayerIdentifiers().getSimpSessionId();
        this.gameSessionId = injection.getGameSessionId();
        this.gameSession = null;
        this.originatingKeycloakId = injection.getKeycloakId();
        this.originatingAuthorities = new HashSet<>(injection.getAuthorities());
    }
}
