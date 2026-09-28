package com.soulsoftworks.sockbowlgame.model.socket.out.progression;


import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSanitizer;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class GameSessionUpdate extends SockbowlOutMessage {

    GameSession gameSession;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.PROGRESSION;
    }

    /**
     * A {@link GameSessionUpdate} for every player in the session, each copy
     * sanitized for its recipient: the proctor (if any) gets the proctor view,
     * everyone else the player view. Sent as targeted messages rather than one
     * broadcast so the proctor's view never reaches other players. Every
     * session update that goes to more than one player must be built here
     * (G2-01).
     */
    public static SockbowlOutMessage sanitizedForEachRecipient(GameSession gameSession) {
        Player proctor = gameSession.getProctor();
        if (proctor == null) {
            // No proctor: everyone gets the same player view, so a broadcast is fine.
            return GameSessionUpdate.builder()
                    .gameSession(GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.SPECTATOR))
                    .build();
        }

        SockbowlMultiOutMessage.SockbowlMultiOutMessageBuilder<?, ?> multi = SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(GameSessionUpdate.builder()
                        .gameSession(GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.PROCTOR))
                        .recipient(proctor.getPlayerId())
                        .build());

        List<String> others = gameSession.getPlayerList().stream()
                .map(Player::getPlayerId)
                .filter(id -> !id.equals(proctor.getPlayerId()))
                .toList();
        // An empty recipient list means "broadcast", which would send the player
        // view over the proctor's, so only add it when someone else is present.
        if (!others.isEmpty()) {
            multi.sockbowlOutMessage(GameSessionUpdate.builder()
                    .gameSession(GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.SPECTATOR))
                    .recipients(others)
                    .build());
        }
        return multi.build();
    }
}
