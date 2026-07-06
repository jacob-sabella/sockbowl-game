package com.soulsoftworks.sockbowlgame.model.packet.relationships;

import com.soulsoftworks.sockbowlgame.model.packet.nodes.BonusPart;
import lombok.Data;

@Data
public class HasBonusPart {

    private Long id;
    private final Integer order; // This stores the order of the BonusPart in the Bonus

    private final BonusPart bonusPart;

    // Constructor
    public HasBonusPart(Integer order, BonusPart bonusPart) {
        this.order = order;
        this.bonusPart = bonusPart;
    }

}