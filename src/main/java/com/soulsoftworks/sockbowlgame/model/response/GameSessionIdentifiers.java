package com.soulsoftworks.sockbowlgame.model.response;

import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameSessionIdentifiers {
    private String id;
    private String joinCode;

    public static class GameSessionIdentifiersBuilder {
        private String id;
        private String joinCode;

        public GameSessionIdentifiersBuilder fromGameSession(GameSession gameSession) {
            this.id = gameSession.getId();
            this.joinCode = gameSession.getJoinCode();
            return this;
        }
    }

}
