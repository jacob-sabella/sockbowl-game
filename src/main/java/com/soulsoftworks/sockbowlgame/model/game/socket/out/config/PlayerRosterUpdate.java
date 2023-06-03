package com.soulsoftworks.sockbowlgame.model.game.socket.out.config;

import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.Team;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
public class PlayerRosterUpdate extends SockbowlOutMessage {

    private List<Player> playerList;
    private List<Team> teamList;

    @Override
    public MessageTypes getMessageType() {
        return MessageTypes.CONFIG;
    }

    public static PlayerRosterUpdate fromGameSession(GameSession gameSession) {
        return PlayerRosterUpdate.builder()
                .playerList(gameSession.getPlayerList())
                .teamList(gameSession.getTeams())
                .build();
    }
}
