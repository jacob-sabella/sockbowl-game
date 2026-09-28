package com.soulsoftworks.sockbowlgame.model.state;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Searchable;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import lombok.*;
import org.springframework.data.annotation.Id;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Keycloak subject (sub claim) of the user who created this session, when it
     * was created by an authenticated user. Null for guest-created sessions, in
     * which case ownership falls back to the first-join-wins
     * {@link Player#isGameOwner()} flag. The authorization policy uses this to
     * tie session ownership to a durable identity rather than an ephemeral
     * in-session player id.
     */
    private String gameOwnerId;

    @Builder.Default
    private List<Player> playerList = new ArrayList<>();

    @Builder.Default
    private List<Team> teamList = new ArrayList<>();

    @Builder.Default
    private Match currentMatch = new Match();

    @Builder.Default
    private List<Match> previousMatches = new ArrayList<>();

    /**
     * For each packet id, the players who held the proctor seat while that
     * packet was loaded in this session, and so could read its answers
     * (R3-G2-03). None of them may play a match on that packet: they are
     * refused a team seat while it is loaded, and a match on it cannot start
     * while one of them is on a team. Server-side only: every client view
     * drops it ({@link GameSanitizer}).
     */
    @Builder.Default
    private Map<String, List<String>> proctorsByPacketId = new HashMap<>();

    /**
     * Add a guest player. Equivalent to {@code addPlayer(joinGameRequest, null)}.
     */
    public Player addPlayer(JoinGameRequest joinGameRequest) {
        return addPlayer(joinGameRequest, null);
    }

    /**
     * Add a player to the session and decide, once, whether they own it. This is
     * the single place the {@link Player#isGameOwner()} flag is set (AUTH-11):
     * <ul>
     *   <li>Session created by an authenticated user ({@link #gameOwnerId} set):
     *       the player owns it only if they joined with that same Keycloak
     *       subject. A guest or a different signed-in user never becomes owner,
     *       even if they happen to join first.</li>
     *   <li>Guest-created session ({@code gameOwnerId == null}): the first
     *       player to join owns it (the pre-auth behaviour).</li>
     * </ul>
     * The flag is what ng reads (it is broadcast); authorization decisions go
     * through {@code GameAuthorizationPolicy.isSessionOwner}, which reads this
     * flag and re-checks the Keycloak subject.
     *
     * @param joinGameRequest the join request (player id and display name)
     * @param keycloakId      the joining user's Keycloak subject, or null for a guest
     * @return the new player
     */
    public Player addPlayer(JoinGameRequest joinGameRequest, String keycloakId) {
        Player player = Player.builder()
                .playerId(joinGameRequest.getPlayerSessionId())
                .name(joinGameRequest.getName())
                .playerMode(PlayerMode.SPECTATOR)
                .playerSecret(UUID.randomUUID()
                        .toString())
                .keycloakId(keycloakId)
                .isGuest(keycloakId == null)
                .build();

        player.setGameOwner(isOwnerOnJoin(keycloakId));

        playerList.add(player);

        return player;
    }

    private boolean isOwnerOnJoin(String keycloakId) {
        if (gameOwnerId != null && !gameOwnerId.isBlank()) {
            return keycloakId != null && keycloakId.equals(gameOwnerId);
        }
        return playerList.isEmpty();
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

    /**
     * Get count of active players (BUZZER mode only, excludes spectators).
     * Used for enforcing player capacity limits while allowing unlimited spectators.
     *
     * @return Number of players in BUZZER mode (on teams)
     */
    public int getActivePlayerCount() {
        return (int) playerList.stream()
                .filter(player -> player.getPlayerMode() == PlayerMode.BUZZER)
                .count();
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

    /**
     * Record that {@code playerId} held the proctor seat while {@code packetId}
     * was loaded (see {@link #proctorsByPacketId}). No-op for a blank id.
     */
    public void recordPacketProctor(String packetId, String playerId) {
        if (packetId == null || packetId.isBlank() || playerId == null) {
            return;
        }
        if (proctorsByPacketId == null) {
            proctorsByPacketId = new HashMap<>();
        }
        List<String> proctors = proctorsByPacketId.computeIfAbsent(packetId, id -> new ArrayList<>());
        if (!proctors.contains(playerId)) {
            proctors.add(playerId);
        }
    }

    /** Whether {@code playerId} held the proctor seat while {@code packetId} was loaded. */
    public boolean hasProctoredPacket(String packetId, String playerId) {
        if (packetId == null || packetId.isBlank() || playerId == null || proctorsByPacketId == null) {
            return false;
        }
        List<String> proctors = proctorsByPacketId.get(packetId);
        return proctors != null && proctors.contains(playerId);
    }

    /** The id of the packet loaded in the current match, or null when none is. Not a bean getter, so no serializer picks it up. */
    public String loadedPacketId() {
        if (currentMatch == null || currentMatch.getPacket() == null) {
            return null;
        }
        String id = currentMatch.getPacket().getId();
        return id == null || id.isBlank() ? null : id;
    }
}
