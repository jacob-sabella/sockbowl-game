package com.soulsoftworks.sockbowlgame.model.game;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Indexed;
import com.redis.om.spring.annotations.Searchable;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.springframework.data.annotation.Id;

import java.util.List;

@Data
@Document(timeToLive = 21600)
@Builder
public class GameSession{
    @Id
    private String id;

    @Searchable
    @Indexed
    @NonNull
    private String joinCode;

    @NonNull
    private GameSettings gameSettings;

    private List<Player> playerList;

    public void addPlayer(JoinGameRequest joinGameRequest){
        Player player = new Player();
        player.setSessionId(joinGameRequest.getSessionId());
        player.setName(joinGameRequest.getName());
        player.setPlayerMode(joinGameRequest.getPlayerMode());
        playerList.add(player);
    }

}
