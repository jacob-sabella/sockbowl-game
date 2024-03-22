package com.soulsoftworks.sockbowlgame.model.packet.relationships;


import com.soulsoftworks.sockbowlgame.model.packet.nodes.Tossup;
import lombok.Data;

@Data
public class ContainsTossup {
    private Long id;
    private Integer order;
    private Tossup tossup;

}
