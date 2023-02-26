package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

import java.util.List;

@Data
public class Packet {
    private int id;
    private String name;
    private List<PacketTossup> tossups;
    private List<PacketBonus> bonuses;
    private Difficulty difficulty;
}
