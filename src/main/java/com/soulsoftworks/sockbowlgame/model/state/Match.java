package com.soulsoftworks.sockbowlgame.model.state;

import com.soulsoftworks.sockbowlgame.model.packet.Packet;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Match {

    private MatchState matchState = MatchState.CONFIG;

    private Packet packet;

    private int currentQuestionNumber = 1;
    private int currentBonusNumber = 1;

}
