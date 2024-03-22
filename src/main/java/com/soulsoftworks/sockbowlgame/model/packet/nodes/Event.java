package com.soulsoftworks.sockbowlgame.model.packet.nodes;

import com.soulsoftworks.sockbowlgame.model.packet.relationships.UsesPacketAtRound;
import lombok.Data;

import java.util.List;

@Data
public class Event {
    private String id;
    private String location;
    private String name;
    private Integer year;
    private Boolean imported;
    private List<UsesPacketAtRound> packets;
}

