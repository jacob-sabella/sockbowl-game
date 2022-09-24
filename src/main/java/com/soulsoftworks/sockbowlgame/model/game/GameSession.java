package com.soulsoftworks.sockbowlgame.model.game;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Indexed;
import com.redis.om.spring.annotations.Searchable;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.data.annotation.Id;

@Data
@Document(timeToLive = 21600)
@Builder
public class GameSession{
    @Id
    private String id;

    @Searchable
    @Indexed
    @NonNull
    private String joinCode;

    @NonNull
    private GameSettings gameSettings;
}
