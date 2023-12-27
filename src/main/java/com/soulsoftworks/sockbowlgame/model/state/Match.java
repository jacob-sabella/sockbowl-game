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
    private Packet packet = new Packet();
    private List<Round> previousRounds = new ArrayList<>();
    private Round currentRound = new Round();

    public void advanceRound(){
        int nextRoundNumber = currentRound.getRoundNumber() + 1;
        String nextRoundQuestion = packet.getTossups().get(nextRoundNumber).getTossup().getQuestion();
        String nextRoundAnswer = packet.getTossups().get(nextRoundNumber).getTossup().getAnswer();

        previousRounds.add(currentRound);
        currentRound = new Round();
        currentRound.setupRound(nextRoundNumber, nextRoundQuestion, nextRoundAnswer);
    }

}
