package com.soulsoftworks.sockbowlgame.service.processor;


import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameStartedMessage;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;


import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createPlayers;
import static org.junit.jupiter.api.Assertions.*;

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
        // A selected packet always carries its persisted id; the default new Packet()
        // (id == null) is the "nothing selected yet" sentinel the start guard rejects.
        mockMatch.getPacket().setId("PACKET-1");
        mockMatch.getPacket().setTossups(new ArrayList<>());
        mockMatch.getPacket().getTossups().add(new ContainsTossup());
        mockMatch.getPacket().getTossups().add(new ContainsTossup());
        mockMatch.getPacket().getTossups().get(0).setTossup(new Tossup());
        mockMatch.getPacket().getTossups().get(1).setTossup(new Tossup());
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
    @DisplayName("Non-proctor player tries to start the match causes error")
    void startMatch_NonProctorPlayerTriesToStartMatch_ReturnsProcessErrorMessage() {
        StartMatch message = StartMatch.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(1).getPlayerId())
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertInstanceOf(ProcessError.class, result);
        assertEquals(ProcessError.accessDeniedMessage(message).getError(),
                ((ProcessError) result).getError());
    }

    @Test
    @DisplayName("Proctor starts the match successfully")
    void startMatch_ProctorPlayerStartsMatch_SuccessfullyStartsMatch() {
        StartMatch message = StartMatch.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(0).getPlayerId())
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertInstanceOf(SockbowlMultiOutMessage.class, result);

        SockbowlMultiOutMessage multiOutMessage = (SockbowlMultiOutMessage) result;

        assertEquals(3, multiOutMessage.getSockbowlOutMessages().size());

        assertInstanceOf(GameStartedMessage.class, multiOutMessage.getSockbowlOutMessages().get(0));
/*        assertTrue(multiOutMessage.getSockbowlOutMessages().get(1) instanceof FullContextTossupUpdate);
        assertTrue(multiOutMessage.getSockbowlOutMessages().get(2) instanceof LimitedContextTossupUpdate);*/

        assertEquals(MatchState.IN_GAME, mockGameSession.getCurrentMatch().getMatchState());
    }

    @Test
    @DisplayName("Starting with no packet selected is rejected cleanly (not an NPE)")
    void startMatch_NoPacketSelected_ReturnsProcessErrorMessage() {
        // Default match packet: new Packet() with a null id — nothing selected.
        mockGameSession.getCurrentMatch().setPacket(new Packet());

        StartMatch message = StartMatch.builder()
                .gameSession(mockGameSession)
                .originatingPlayerId(playerList.get(0).getPlayerId()) // proctor
                .build();

        SockbowlOutMessage result = processor.startMatch(message);

        assertInstanceOf(ProcessError.class, result);
        assertEquals("A packet must be selected for the match.", ((ProcessError) result).getError());
        assertEquals(MatchState.CONFIG, mockGameSession.getCurrentMatch().getMatchState());
    }

}
