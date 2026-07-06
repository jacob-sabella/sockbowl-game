package com.soulsoftworks.sockbowlgame.model.state;

import com.soulsoftworks.sockbowlgame.model.packet.nodes.Bonus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a round in the game. Includes information about the round state,
 * the round number, the current and all past buzzes, the question and answer,
 * and whether the proctor has finished reading the question.
 * Also manages bonus question state if bonuses are enabled.
 */
@Data
public class Round {
    private RoundState roundState;
    private int roundNumber;
    private Buzz currentBuzz;
    private List<Buzz> buzzList = new ArrayList<>();
    private String question;
    private String answer;
    private String category;
    private String subcategory;
    private boolean proctorFinishedReading = false;

    // Bonus-related fields
    private Bonus associatedBonus;  // Bonus for this tossup (set during advanceRound)
    private Bonus currentBonus;  // Active bonus during bonus phase
    private List<BonusPartAnswer> bonusPartAnswers = new ArrayList<>();
    private int currentBonusPartIndex = 0;
    private String bonusEligibleTeamId;  // Team that earned the right to answer bonus
    private boolean proctorFinishedReadingBonusPreamble = false;
    private boolean proctorFinishedReadingCurrentPart = false;

    // Timer-related fields
    private Integer remainingTossupTimerSeconds;  // null = no active timer
    private Integer remainingBonusTimerSeconds;   // null = no active timer
    private Long timerStartedAtMillis;            // timestamp when timer started

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
     * adds it to the buzz list. Note: Does NOT change the round state to COMPLETED
     * to allow the caller to handle bonus phase logic if bonuses are enabled.
     */
    public void processCorrectAnswer(){
        // Mark the current buzz as correct
        currentBuzz.setCorrect(true);

        // Add the buzz to the list
        buzzList.add(currentBuzz);

        // Reset the current buzz
        currentBuzz = null;

        // NOTE: Round state is NOT set to COMPLETED here
        // The caller (GameMessageProcessor) will handle state based on bonus settings
    }

    /**
     * Sets up a new round with the given round number, question, and answer.
     *
     * @param roundNumber the number of the round
     * @param question the question for the round
     * @param answer the answer for the question
     */
    public void setupRound(int roundNumber, String question, String answer, String category, String subcategory){
        this.roundNumber = roundNumber;
        this.question = question;
        this.answer = answer;
        this.roundState = RoundState.PROCTOR_READING;
	this.category = category;
	this.subcategory = subcategory;
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

    /**
     * Sets up the bonus phase after a correct tossup answer.
     *
     * @param bonus The bonus to be answered
     * @param teamId Team that gets to answer the bonus
     */
    public void setupBonus(Bonus bonus, String teamId) {
        this.currentBonus = bonus;
        this.bonusEligibleTeamId = teamId;
        this.currentBonusPartIndex = 0;
        this.bonusPartAnswers = new ArrayList<>();
        this.proctorFinishedReadingBonusPreamble = false;
        this.proctorFinishedReadingCurrentPart = false;
        this.roundState = RoundState.BONUS_READING_PREAMBLE;
    }

    /**
     * Processes a bonus part answer from the proctor.
     * Note: Does not change state - that is handled by advanceToNextBonusPart().
     *
     * @param partIndex Index of the bonus part (0-2)
     * @param correct Whether the answer was correct
     */
    public void processBonusPartAnswer(int partIndex, boolean correct) {
        BonusPartAnswer bonusPartAnswer = new BonusPartAnswer();
        bonusPartAnswer.setPartIndex(partIndex);
        bonusPartAnswer.setCorrect(correct);
        bonusPartAnswers.add(bonusPartAnswer);
    }

    /**
     * Transitions from reading state to awaiting answer with timer.
     */
    public void startBonusPartTimer() {
        this.proctorFinishedReadingCurrentPart = true;
        this.roundState = RoundState.BONUS_AWAITING_ANSWER;
    }

    /**
     * Advances to the next bonus part after current part is judged.
     * Sets up reading state for the next part or completes bonus phase.
     */
    public void advanceToNextBonusPart() {
        currentBonusPartIndex++;

        if (currentBonusPartIndex >= 3) {
            // All parts complete
            this.roundState = RoundState.BONUS_COMPLETED;
        } else {
            // Move to reading next part
            this.proctorFinishedReadingCurrentPart = false;
            this.roundState = RoundState.BONUS_READING_PART;
        }
    }

    /**
     * Handles timeout for current bonus part.
     * Marks the current part as incorrect and advances to next part.
     */
    public void timeoutBonusPart() {
        // Auto-mark current part as incorrect
        BonusPartAnswer bonusPartAnswer = new BonusPartAnswer();
        bonusPartAnswer.setPartIndex(currentBonusPartIndex);
        bonusPartAnswer.setCorrect(false);
        bonusPartAnswers.add(bonusPartAnswer);

        // Advance to next part
        advanceToNextBonusPart();
    }

    /**
     * Calculates total bonus points earned.
     *
     * @return Total bonus points (0-30)
     */
    public int getBonusPoints() {
        return (int) bonusPartAnswers.stream()
                .filter(BonusPartAnswer::isCorrect)
                .count() * 10;
    }

    /**
     * Checks if this round has an associated bonus.
     *
     * @return true if there is an associated bonus, false otherwise
     */
    public boolean hasAssociatedBonus() {
        return associatedBonus != null;
    }

    /**
     * Starts the tossup timer with the specified duration.
     *
     * @param durationSeconds Timer duration in seconds
     */
    public void startTossupTimer(int durationSeconds) {
        this.remainingTossupTimerSeconds = durationSeconds;
        this.timerStartedAtMillis = System.currentTimeMillis();
    }

    /**
     * Starts the bonus timer with the specified duration.
     *
     * @param durationSeconds Timer duration in seconds
     */
    public void startBonusTimer(int durationSeconds) {
        this.remainingBonusTimerSeconds = durationSeconds;
        this.timerStartedAtMillis = System.currentTimeMillis();
    }

    /**
     * Clears the tossup timer.
     */
    public void clearTossupTimer() {
        this.remainingTossupTimerSeconds = null;
        this.timerStartedAtMillis = null;
    }

    /**
     * Clears the bonus timer.
     */
    public void clearBonusTimer() {
        this.remainingBonusTimerSeconds = null;
        this.timerStartedAtMillis = null;
    }

    /**
     * Checks if the tossup timer is currently active.
     *
     * @return true if timer is active and has time remaining, false otherwise
     */
    public boolean isTossupTimerActive() {
        return remainingTossupTimerSeconds != null && remainingTossupTimerSeconds > 0;
    }

    /**
     * Checks if the bonus timer is currently active.
     *
     * @return true if timer is active and has time remaining, false otherwise
     */
    public boolean isBonusTimerActive() {
        return remainingBonusTimerSeconds != null && remainingBonusTimerSeconds > 0;
    }

}
