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
        // Clear timers from previous round
        if (currentRound != null) {
            currentRound.clearTossupTimer();
            currentRound.clearBonusTimer();
        }

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
	    

	    String nextRoundSubcategory = "";
            String nextRoundCategory = "";

            try {
            	nextRoundSubcategory = packet.getTossups().get(nextRoundNumber).getTossup().getSubcategory().getName();
                nextRoundCategory = packet.getTossups().get(nextRoundNumber).getTossup().getSubcategory().getCategory().getName();
	    } catch (Exception e){
	        //
	    }
	   	
            if(nextRoundNumber - 1 != 0){
                previousRounds.add(currentRound);
            }

            currentRound = new Round();
            currentRound.setupRound(nextRoundNumber, nextRoundQuestion, nextRoundAnswer, nextRoundCategory, nextRoundSubcategory);

            // Check if there's a bonus for this tossup (same index)
            if (packet.getBonuses() != null && nextRoundNumber < packet.getBonuses().size()) {
                try {
                    currentRound.setAssociatedBonus(packet.getBonuses().get(nextRoundNumber).getBonus());
                } catch (Exception e) {
                    // Bonus might be null, that's okay
                }
            }
        }
    }

    public void completeRound(){
        currentRound.setRoundState(RoundState.COMPLETED);
    }

    /**
     * Transitions round from tossup completion to bonus phase.
     * Called after correct tossup answer if bonuses enabled.
     *
     * @param teamId Team that gets to answer the bonus
     */
    public void startBonusPhase(String teamId) {
        if (currentRound.hasAssociatedBonus()) {
            currentRound.setupBonus(currentRound.getAssociatedBonus(), teamId);
        } else {
            // No bonus for this tossup, skip to completed
            currentRound.setRoundState(RoundState.COMPLETED);
        }
    }

    /**
     * Completes the bonus phase and marks round as completed.
     */
    public void completeBonusPhase() {
        currentRound.setRoundState(RoundState.COMPLETED);
    }

}
