package com.soulsoftworks.sockbowlgame.model.packet.nodes;

import com.soulsoftworks.sockbowlgame.model.packet.relationships.HasBonusPart;
import lombok.Data;

import java.util.List;

@Data
public class Bonus {
    private String id;
    private String preamble;

    private Subcategory subcategory;

    private List<HasBonusPart> bonusParts;
}
