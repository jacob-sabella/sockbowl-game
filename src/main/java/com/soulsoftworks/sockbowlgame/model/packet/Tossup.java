package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

@Data
public class Tossup {
    private int id;
    private String question;
    private int subcategoryId;
    private String answer;
    private Subcategory subcategory;
}
