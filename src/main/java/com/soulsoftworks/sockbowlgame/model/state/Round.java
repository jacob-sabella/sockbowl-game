package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a round in the game. Includes information about the round state,
 * the round number, the current and all past buzzes, the question and answer,
 * and whether the proctor has finished reading the question.
 */
@Data
public class Round {
    private RoundState roundState;
    private int roundNumber;
    private Buzz currentBuzz;
    private List<Buzz> buzzList = new ArrayList<>();
    private String question;
    private String answer;
    private boolean proctorFinishedReading = false;

    /**
     * Processes a buzz action. Adds the current buzz to the buzz list if it exists,
     * then creates a new buzz with the given player and team IDs, and changes the round state
     * to AWAITING_ANSWER.
     *
     * @param playerId ID of the player who buzzed
     * @param teamId   ID of the team the player belongs to
     */
    public void processBuzz(String playerId, String teamId){
        // Add the current buzz to the list if it exists
        if(this.currentBuzz != null){
            this.buzzList.add(currentBuzz);
        }
        // Create a new buzz with the provided player and team IDs
        this.currentBuzz = new Buzz();
        this.currentBuzz.setTeamId(teamId);
        this.currentBuzz.setPlayerId(playerId);
        // Change the round state to AWAITING_ANSWER
        this.roundState = RoundState.AWAITING_ANSWER;
    }

    /**
     * Processes an incorrect answer. Marks the current buzz as incorrect,
     * adds it to the buzz list, then changes the round state depending on
     * whether the proctor has finished reading the question.
     */
    public void processIncorrectAnswer(){
        // Mark the current buzz as incorrect
        currentBuzz.setCorrect(false);

        // Add the buzz to the list
        buzzList.add(currentBuzz);

        // Reset the current buzz
        currentBuzz = null;

        // Change the round state depending on whether the proctor finished reading
        if(!proctorFinishedReading){
            this.roundState = RoundState.PROCTOR_READING;
        } else{
            this.roundState = RoundState.AWAITING_BUZZ;
        }
    }

    /**
     * Processes a correct answer. Marks the current buzz as correct,
     * adds it to the buzz list, then changes the round state to COMPLETED.
     */
    public void processCorrectAnswer(){
        // Mark the current buzz as correct
        currentBuzz.setCorrect(true);

        // Add the buzz to the list
        buzzList.add(currentBuzz);

        // Reset the current buzz
        currentBuzz = null;

        // Change the round state to COMPLETED
        this.roundState = RoundState.COMPLETED;
    }

    /**
     * Sets up a new round with the given round number, question, and answer.
     *
     * @param roundNumber the number of the round
     * @param question the question for the round
     * @param answer the answer for the question
     */
    public void setupRound(int roundNumber, String question, String answer){
        this.roundNumber = roundNumber;
        this.question = question;
        this.answer = answer;
    }

    /**
     * Checks if a team with a given team ID has buzzed in this round.
     *
     * @param teamId the ID of the team
     * @return true if the team has buzzed, false otherwise
     */
    public boolean hasTeamBuzzed(String teamId) {
        // Go through all buzzes in the list
        for(Buzz buzz : buzzList) {
            // If a buzz from the given team is found, return true
            if(buzz.getTeamId().equals(teamId)) {
                return true;
            }
        }
        // If no buzz from the given team is found, return false
        return false;
    }

}
