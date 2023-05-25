package com.soulsoftworks.sockbowlgame.model.game.socket.in.config;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlMessage;

public class UpdatePlayerTeam extends SockbowlMessage {



    @Override
    public String getMessageType() {
        return MessageTypes.In.UPDATE_PLAYER_TEAM;
    }
}
