package com.soulsoftworks.sockbowlgame.model.packet.nodes;


import lombok.Data;
import java.util.List;


@Data
public class Category {
    private String id;
    private String name;
    private List<Subcategory> subcategories;
}