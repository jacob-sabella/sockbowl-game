package com.soulsoftworks.sockbowlgame.model.state;

import com.soulsoftworks.sockbowlgame.model.packet.Packet;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class Match {
    private MatchState matchState = MatchState.CONFIG;
    private Packet packet;
    private List<Round> previousRounds = new ArrayList<>();
    private Round currentRound = new Round();
}
