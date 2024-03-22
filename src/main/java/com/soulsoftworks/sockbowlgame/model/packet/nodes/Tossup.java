package com.soulsoftworks.sockbowlgame.model.packet.nodes;

import lombok.Data;


@Data
public class Tossup {
    private String id;
    private String question;
    private String answer;
    private Subcategory subcategory;
}
