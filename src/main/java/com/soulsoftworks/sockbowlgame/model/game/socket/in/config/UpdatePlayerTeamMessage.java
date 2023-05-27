package com.soulsoftworks.sockbowlgame.model.game.socket.in.config;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import lombok.experimental.SuperBuilder;

@SuperBuilder
public class UpdatePlayerTeamMessage extends SockbowlInMessage {

    private String targetPlayer;
    private String targetTeam;

    public UpdatePlayerTeamMessage(String originatingPlayerId, String gameSessionId, String targetPlayer,
                                   String targetTeam) {
        super(originatingPlayerId, gameSessionId);
    }

    @Override
    public String getMessageType() {
        return MessageTypes.In.Config.UPDATE_PLAYER_TEAM;
    }
}
