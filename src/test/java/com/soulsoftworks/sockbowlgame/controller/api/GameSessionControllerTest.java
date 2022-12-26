package com.soulsoftworks.sockbowlgame.controller.api;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.model.game.GameMode;
import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.GameSettings;
import com.soulsoftworks.sockbowlgame.model.game.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class GameSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Gson gson = new Gson();

    @MockBean
    private GameSessionService gameSessionService;

    @InjectMocks
    MockHttpSession mockHttpSession;

    private GameSettings gameSettings;
    private GameSession gameSession;
    private CreateGameRequest createGameRequest;

    @BeforeEach
    private void beforeAll() {
        gameSettings = GameSettings.builder()
                .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                .numPlayers(2)
                .numTeams(2)
                .build();

        createGameRequest = CreateGameRequest.builder()
                .gameSettings(gameSettings)
                .build();

        gameSession = GameSession.builder()
                .id("TEST")
                .joinCode("TEST")
                .gameSettings(gameSettings)
                .build();
    }


    @Test
    public void createNewGame_gameSessionIdentifiersAreReturnedAsExpected() throws Exception {

        // Return game session when asked via service
        when(gameSessionService.createNewGame(any())).thenReturn(gameSession);

        // Create expected output
        GameSessionIdentifiers expectedGameSessionIdentifiers = GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();

        // Run endpoint and get response
        ResultActions resultActions = this.mockMvc.perform(post("/api/v1/session/create-new-game-session")
                        .content(gson.toJson(gameSettings)))
                .andDo(print())
                .andExpect(status().isOk());

        // Convert result back to GameSessionIdentifiers
        MvcResult result = resultActions.andReturn();
        String contentAsString = result.getResponse().getContentAsString();
        GameSessionIdentifiers response = objectMapper.readValue(contentAsString, GameSessionIdentifiers.class);

        assertEquals(expectedGameSessionIdentifiers, response);
    }


    @Test
    public void joinGameSessionWithCode_joiningGameSessionReturnExpectedResult() throws Exception {

        // Return game session when asked via service
        when(gameSessionService.addPlayerToGameSessionWithJoinCode(any())).thenReturn(JoinStatus.SUCCESS);

        // Create expected output
        GameSessionIdentifiers expectedGameSessionIdentifiers = GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();

        // Run endpoint and get response
        ResultActions resultActions = this.mockMvc.perform(post("/api/v1/session/join-game-session-by-code")
                        .session(mockHttpSession))
                .andDo(print())
                .andExpect(status().isOk());

        // Convert result back to JoinGameResponse
        MvcResult result = resultActions.andReturn();
        String contentAsString = result.getResponse().getContentAsString();
        JoinGameResponse response = objectMapper.readValue(contentAsString, JoinGameResponse.class);

        assertEquals(JoinStatus.SUCCESS, response.getJoinStatus());
        assertEquals("1", response.getSessionId());
    }

}
