package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.EndMatch;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.RoundUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.util.DeepCopyUtil;
import org.springframework.stereotype.Service;

import static com.soulsoftworks.sockbowlgame.model.state.GameSanitizer.sanitizeRound;

@Service
public class ProgressionMessageProcessor extends MessageProcessor {

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(StartMatch.class, this::startMatch);
        processorMapping.registerProcessor(EndMatch.class, this::endMatch);
    }

    public SockbowlOutMessage endMatch(SockbowlInMessage endMatchMessage) {

        GameSession gameSession = endMatchMessage.getGameSession();

        // Check if the player making the request is the proctor
        if (gameSession.getProctor() == null ||
            !gameSession.getProctor().getPlayerId().equals(endMatchMessage.getOriginatingPlayerId())) {
            // If not, return access denied error message
            return ProcessError.accessDeniedMessage(endMatchMessage);
        }

        // Retrieve the current match
        Match currentMatch = gameSession.getCurrentMatch();

        // Deep copy the current match and add it to the previous matches list
        Match completedMatch = DeepCopyUtil.deepCopy(currentMatch, Match.class);
        gameSession.getPreviousMatches().add(completedMatch);

        // Clear all timers before ending match
        if (gameSession.getCurrentRound() != null) {
            gameSession.getCurrentRound().clearTossupTimer();
            gameSession.getCurrentRound().clearBonusTimer();
        }

        // Set up a new match in config mode
        Match newMatch = new Match();
        gameSession.setCurrentMatch(newMatch);

        // Return a sanitized game session update with Proctor privileges since there is nothing to hide anymore
        GameSession gameSessionSanitized = GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.PROCTOR);
        return GameSessionUpdate.builder()
                .gameSession(gameSessionSanitized)
                .build();
    }


    public SockbowlOutMessage startMatch(SockbowlInMessage startMatchMessage){

        GameSession gameSession = startMatchMessage.getGameSession();

        boolean singlePlayer = gameSession.getGameSettings().getGameMode() == GameMode.SINGLE_PLAYER;

        // Authorize the starter: single player has no proctor, so the game owner starts;
        // otherwise the proctor must be the one starting.
        if (singlePlayer) {
            Player starter = gameSession.getPlayerById(startMatchMessage.getOriginatingPlayerId());
            if (starter == null || !starter.isGameOwner()) {
                return ProcessError.accessDeniedMessage(startMatchMessage);
            }
        } else if (gameSession.getProctor() == null ||
                !gameSession.getProctor().getPlayerId().equals(startMatchMessage.getOriginatingPlayerId())) {
            return ProcessError.accessDeniedMessage(startMatchMessage);
        }

        // Verify that a packet has been selected
        if ("".equals(gameSession.getCurrentMatch().getPacket().getId())) {
            // If packet ID is 90, return error message
            return ProcessError.builder().error("A packet must be selected for the match.").build();
        }

        // Verify that a proctored match has a proctor (single player needs none)
        if (!singlePlayer && gameSession.getProctor() == null) {
            return ProcessError.builder().error("No proctor assigned to the match.").build();
        }

        // Verify that each team has at least one player
        for (Team team : gameSession.getTeamList()) {
            if (team.getTeamPlayers() == null || team.getTeamPlayers().isEmpty()) {
                // If a team has no players, return error message
                return ProcessError.builder().error("Each team must have at least one player.").build();
            }
        }

        // Change the match state to in game
        gameSession.getCurrentMatch().setMatchState(MatchState.IN_GAME);

        // Set up the first round
        gameSession.getCurrentMatch().advanceRound();

        // Single player: one broadcast round update with the question visible and the
        // answer hidden — no proctor to receive a full-context copy.
        if (singlePlayer) {
            RoundUpdate roundUpdate = RoundUpdate.builder()
                    .round(GameSanitizer.revealQuestionHideAnswer(gameSession.getCurrentRound()))
                    .build();
            return SockbowlMultiOutMessage.builder()
                    .sockbowlOutMessage(new GameStartedMessage())
                    .sockbowlOutMessage(roundUpdate)
                    .build();
        }

        // Create a full context round update message to send to proctor
        RoundUpdate fullContextRoundUpdate = RoundUpdate
                .builder()
                .round(gameSession.getCurrentRound())
                .recipient(gameSession.getProctor().getPlayerId())
                .build();

        // Create sanitized round update for other players
        RoundUpdate limitedContextRoundUpdate = RoundUpdate.builder()
                .round(sanitizeRound(gameSession.getCurrentRound()))
                .recipients(gameSession.getPlayerList().stream()
                        .map(Player::getPlayerId)
                        .filter(playerId -> !playerId.equals(gameSession.getProctor().getPlayerId()))
                        .toList())
                .build();


        // Send multi-message back to processor
        return SockbowlMultiOutMessage
                .builder()
                .sockbowlOutMessage(new GameStartedMessage())
                .sockbowlOutMessage(fullContextRoundUpdate)
                .sockbowlOutMessage(limitedContextRoundUpdate)
                .build();
    }

}
