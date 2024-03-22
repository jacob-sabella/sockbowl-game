package com.soulsoftworks.sockbowlgame.model.packet.nodes;

import com.soulsoftworks.sockbowlgame.model.packet.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlgame.model.packet.relationships.ContainsTossup;
import lombok.Data;

import java.util.List;

@Data
public class Packet {
    private long id;
    private String name;
    private Difficulty difficulty;
    private List<ContainsTossup> tossups;
    private List<ContainsBonus> bonuses;
}