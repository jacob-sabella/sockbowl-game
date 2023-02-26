package com.soulsoftworks.sockbowlgame.model.packet;

import lombok.Data;

@Data
public class Subcategory {
    private int id;
    private String name;
    private int categoryId;
    private Category category;
}
