package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

import java.util.List;

@Data
public class Bonus {
    private int id;
    private int subcategoryId;
    private String preamble;
    private List<BonusPart> bonusParts;
    private Subcategory subcategory;
}
