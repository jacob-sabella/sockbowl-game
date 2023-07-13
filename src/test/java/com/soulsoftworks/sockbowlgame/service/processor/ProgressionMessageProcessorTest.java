package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.packet.Packet;
import com.soulsoftworks.sockbowlgame.model.packet.PacketTossup;
import com.soulsoftworks.sockbowlgame.model.packet.Tossup;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.FullContextTossupUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.LimitedContextTossupUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createPlayers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProgressionMessageProcessorTest {

    private ProgressionMessageProcessor processor;

    private GameSession mockGameSession;

    private final List<Player> playerList = createPlayers(2);

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        processor = new ProgressionMessageProcessor();

        Match mockMatch = new Match();
        mockMatch.setMatchState(MatchState.CONFIG);
        mockMatch.setPacket(new Packet());
        mockMatch.getPacket().setTossups(new ArrayList<>());
        mockMatch.getPacket().getTossups().add(new PacketTossup());
        mockMatch.getPacket().getTossups().get(0).setTossup(new Tossup());
        playerList.get(0).setPlayerMode(PlayerMode.PROCTOR);

        mockGameSession = GameSession.builder()
                .id("TEST")
                .joinCode("TEST")
                .playerList(playerList)
                .currentMatch(mockMatch)
                .gameSettings(new GameSettings())
                .build();
    }

    @Test
    @DisplayName("Non-owner player tries to start the match causes error")
    void startMatch_NonOwnerPlayerTriesToStartMatch_ReturnsProcessErrorMessage() {
        StartMatch message = StartMatch.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(1).getPlayerId())
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertTrue(result instanceof ProcessError);
        assertEquals(ProcessError.accessDeniedMessage(message).getError(),
                ((ProcessError) result).getError());
    }

    @Test
    @DisplayName("Game owner starts the match successfully")
    void startMatch_OwnerPlayerStartsMatch_SuccessfullyStartsMatch() {
        StartMatch message = StartMatch.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(0).getPlayerId())
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertTrue(result instanceof SockbowlMultiOutMessage);

        SockbowlMultiOutMessage multiOutMessage = (SockbowlMultiOutMessage) result;

        assertEquals(3, multiOutMessage.getSockbowlOutMessages().size());

        assertTrue(multiOutMessage.getSockbowlOutMessages().get(0) instanceof GameStartedMessage);
        assertTrue(multiOutMessage.getSockbowlOutMessages().get(1) instanceof FullContextTossupUpdate);
        assertTrue(multiOutMessage.getSockbowlOutMessages().get(2) instanceof LimitedContextTossupUpdate);

        assertEquals(MatchState.IN_GAME, mockGameSession.getCurrentMatch().getMatchState());
    }

}
