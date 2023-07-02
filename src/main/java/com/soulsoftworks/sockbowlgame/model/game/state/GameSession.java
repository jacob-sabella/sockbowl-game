package com.soulsoftworks.sockbowlgame.model.game.state;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Searchable;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import lombok.*;
import org.springframework.data.annotation.Id;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Document(timeToLive = 21600)
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
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

    @Builder.Default
    private List<Team> teams = new ArrayList<>();

    @Builder.Default
    private Match currentMatch = new Match();

    public void addPlayer(JoinGameRequest joinGameRequest){
        Player player = Player.builder()
                .playerId(joinGameRequest.getPlayerSessionId())
                .name(joinGameRequest.getName())
                .playerMode(joinGameRequest.getPlayerMode())
                .playerSecret(UUID.randomUUID().toString())
                .build();

        // First player to join is the game owner
        if(playerList.size() == 0){
            player.setGameOwner(true);
        }

        playerList.add(player);
    }

    /**
     * Retrieves a Player from the game session by their player ID.
     *
     * @param playerId The unique ID of the player.
     * @return Player object if found, else null.
     */
    public Player getPlayerById(String playerId){
        // Use a stream to search through the player list for a match on player ID
        return this.playerList.stream()
                .filter(player -> player.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }


    public Team findTeamWithId(String teamId) {
        return teams.stream()
                .filter(team -> team.getTeamId().equals(teamId))
                .findFirst()
                .orElse(null);
    }

    public Team getTeamByPlayerId(String playerId) {
        return teams.stream()
                .filter(team -> team.isPlayerOnTeam(playerId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Retrieves the proctor player from the game session.
     *
     * @return Player object if found, else null.
     */
    public Player getProctor() {
        // Use a stream to search through the player list for the player in PROCTOR mode
        return this.playerList.stream()
                .filter(player -> player.getPlayerMode() == PlayerMode.PROCTOR)
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if the player with the given player ID is the game owner.
     *
     * @param playerId The unique ID of the player.
     * @return true if the player is the game owner, false otherwise.
     */
    public boolean isPlayerGameOwner(String playerId) {
        return this.playerList.stream()
                .filter(player -> player.getPlayerId().equals(playerId))
                .map(Player::isGameOwner)
                .findFirst()
                .orElse(false);
    }

}
