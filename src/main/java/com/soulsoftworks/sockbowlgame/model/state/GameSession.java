package com.soulsoftworks.sockbowlgame.model.state;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Searchable;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import lombok.*;
import org.springframework.data.annotation.Id;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Data
@Document(timeToLive = 21600)
@Builder(toBuilder = true)
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class GameSession {
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
    private List<Team> teamList = new ArrayList<>();

    @Builder.Default
    private Match currentMatch = new Match();

    public Player addPlayer(JoinGameRequest joinGameRequest) {
        Player player = Player.builder()
                .playerId(joinGameRequest.getPlayerSessionId())
                .name(joinGameRequest.getName())
                .playerMode(PlayerMode.SPECTATOR)
                .playerSecret(UUID.randomUUID()
                        .toString())
                .build();

        // First player to join is the game owner
        if (playerList.isEmpty()) {
            player.setGameOwner(true);
        }

        playerList.add(player);

        return player;
    }


    /**
     * Get the current round of the current match
     *
     * @return Current active Round object
     */
    public Round getCurrentRound() {
        return getCurrentMatch().getCurrentRound();
    }

    /**
     * Retrieves a Player from the game session by their player ID.
     *
     * @param playerId The unique ID of the player.
     * @return Player object if found, else null.
     */
    public Player getPlayerById(String playerId) {
        // Use a stream to search through the player list for a match on player ID
        return this.playerList.stream()
                .filter(player -> player.getPlayerId()
                        .equals(playerId))
                .findFirst()
                .orElse(null);
    }


    public Team findTeamWithId(String teamId) {
        return teamList.stream()
                .filter(team -> team.getTeamId()
                        .equals(teamId))
                .findFirst()
                .orElse(null);
    }

    public Team getTeamByPlayerId(String playerId) {
        return teamList.stream()
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
                .filter(player -> player.getPlayerId()
                        .equals(playerId))
                .map(Player::isGameOwner)
                .findFirst()
                .orElse(false);
    }

    /**
     * Retrieves the player mode of a player from the game session by their player ID.
     *
     * @param playerId The unique ID of the player.
     * @return PlayerMode object if found, else null.
     */
    public PlayerMode getPlayerModeById(String playerId) {
        // Use a stream to search through the player list for a match on player ID
        Optional<Player> playerOptional = this.playerList.stream()
                .filter(player -> player.getPlayerId()
                        .equals(playerId))
                .findFirst();

        return playerOptional.map(Player::getPlayerMode)
                .orElse(null);
    }

}
