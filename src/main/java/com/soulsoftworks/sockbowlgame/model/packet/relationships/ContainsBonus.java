package com.soulsoftworks.sockbowlgame.model.packet.relationships;

import com.soulsoftworks.sockbowlgame.model.packet.nodes.Bonus;
import lombok.Data;

@Data
public class ContainsBonus {
    private Long id;
    private Integer order;
    private Bonus bonus;
}
