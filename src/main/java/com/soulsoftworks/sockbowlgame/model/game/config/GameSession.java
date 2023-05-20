package com.soulsoftworks.sockbowlgame.model.game.config;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Searchable;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.springframework.data.annotation.Id;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Document(timeToLive = 21600)
@Builder
public class GameSession{
    @Id
    private String id;

    @Searchable
    @NonNull
    private String joinCode;

    @NonNull
    private GameSettings gameSettings;

    @Builder.Default
    private List<Player> playerList = new ArrayList<>();

    public void addPlayer(JoinGameRequest joinGameRequest){
        Player player = new Player();
        player.setPlayerId(joinGameRequest.getPlayerSessionId());
        player.setName(joinGameRequest.getName());
        player.setPlayerMode(joinGameRequest.getPlayerMode());
        player.setPlayerSecret(UUID.randomUUID().toString());

        // First player to join is the game owner
        if(playerList.size() == 0){
            player.setGameOwner(true);
        }

        playerList.add(player);
    }

}
