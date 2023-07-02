package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.progression.StartMatchMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.error.ProcessErrorMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.MatchState;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.Match;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MatchProgressionMessageProcessorTest {

    private MatchProgressionMessageProcessor processor;

    private GameSession mockGameSession;

    private final List<Player> playerList = createPlayers(2);

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        processor = new MatchProgressionMessageProcessor();

        Match mockMatch = new Match();
        mockMatch.setMatchState(MatchState.CONFIG);

        mockGameSession = GameSession.builder()
                .id("TEST")
                .joinCode("TEST")
                .playerList(playerList)
                .currentMatch(mockMatch)
                .build();
    }

    @Test
    @DisplayName("Non-owner player tries to start the match causes error")
    void startMatch_NonOwnerPlayerTriesToStartMatch_ReturnsProcessErrorMessage() {
        StartMatchMessage message = StartMatchMessage.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(1).getPlayerId())
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertTrue(result instanceof ProcessErrorMessage);
        assertEquals(ProcessErrorMessage.accessDeniedMessage(message).getError(),
                ((ProcessErrorMessage) result).getError());
    }

    @Test
    @DisplayName("Game owner starts the match successfully")
    void startMatch_OwnerPlayerStartsMatch_SuccessfullyStartsMatch() {
        StartMatchMessage message = StartMatchMessage.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(0).getPlayerId())
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertTrue(result instanceof GameStartedMessage);
        assertEquals(MatchState.IN_GAME, mockGameSession.getCurrentMatch().getMatchState());
    }

    private List<Player> createPlayers(int numberOfPlayers) {
        return IntStream.rangeClosed(1, numberOfPlayers)
                .mapToObj(i -> {
                    Player player = Player.builder()
                            .playerId("TEST-PLAYER-" + i + "-ID")
                            .playerSecret("TEST-PLAYER-" + i + "-SECRET")
                            .build();
                    if(i == 1){
                        player.setGameOwner(true);
                    }

                    return player;
                })
                .collect(Collectors.toList());
    }
}
