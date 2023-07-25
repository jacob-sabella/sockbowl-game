package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Round {
    private RoundState roundState;
    private int roundNumber;
    private Buzz currentBuzz;
    private List<Buzz> buzzList = new ArrayList<>();
    private String question;
    private String answer;
    private boolean proctorFinishedReading = false;

    public void processBuzz(String playerId, String teamId){
        if(this.currentBuzz != null){
            this.buzzList.add(currentBuzz);
        }
        this.currentBuzz = new Buzz();
        this.currentBuzz.setTeamId(teamId);
        this.currentBuzz.setPlayerId(playerId);
        this.roundState = RoundState.AWAITING_ANSWER;
    }

    public void processIncorrectAnswer(){
        currentBuzz.setCorrect(false);
        buzzList.add(currentBuzz);
        currentBuzz = null;

        if(!proctorFinishedReading){
            this.roundState = RoundState.PROCTOR_READING;
        } else{
            this.roundState = RoundState.AWAITING_BUZZ;
        }
    }

    public void setupRound(int roundNumber, String question, String answer){
        this.roundNumber = roundNumber;
        this.question = question;
        this.answer = answer;
    }
}
