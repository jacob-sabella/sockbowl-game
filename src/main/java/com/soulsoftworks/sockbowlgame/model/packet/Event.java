package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

import java.util.List;

@Data
public class Event {
    private int id;
    private String name;
    private Integer year;
    private String location;
    private boolean imported;
    private List<EventPacket> eventPackets;
}
