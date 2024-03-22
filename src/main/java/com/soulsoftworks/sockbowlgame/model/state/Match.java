package com.soulsoftworks.sockbowlgame.model.state;

import com.soulsoftworks.sockbowlgame.model.packet.nodes.Packet;
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

    public void advanceRound() {
        int nextRoundNumber = currentRound.getRoundNumber() + 1;

        // Check if the nextRoundNumber exceeds the number of tossups in the packet
        if (nextRoundNumber >= packet.getTossups().size()) {
            // No more tossups left, complete the match
            matchState = MatchState.COMPLETED;

            if(nextRoundNumber - 1 != 0){
                previousRounds.add(currentRound);
            }

            currentRound = null; // Null out the current round
        } else {
            // Proceed with setting up the next round
            String nextRoundQuestion = packet.getTossups().get(nextRoundNumber).getTossup().getQuestion();
            String nextRoundAnswer = packet.getTossups().get(nextRoundNumber).getTossup().getAnswer();

            if(nextRoundNumber - 1 != 0){
                previousRounds.add(currentRound);
            }

            currentRound = new Round();
            currentRound.setupRound(nextRoundNumber, nextRoundQuestion, nextRoundAnswer);
        }
    }

    public void completeRound(){
        currentRound.setRoundState(RoundState.COMPLETED);
    }

}
