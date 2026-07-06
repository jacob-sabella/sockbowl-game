package com.soulsoftworks.sockbowlgame.model.packet.nodes;

import lombok.Data;

import java.util.List;


@Data
public class Subcategory {
    private String id;
    private String name;

    private Category category;

    private List<Bonus> bonuses;

    private List<Tossup> tossups;
}
