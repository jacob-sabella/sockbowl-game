package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;


@Data
public class BonusPart {
    private int bonusId;
    private String question;
    private String answer;
    private int number;
}
