package com.soulsoftworks.sockbowlgame.util;


import com.soulsoftworks.sockbowlgame.model.packet.nodes.*;
import com.soulsoftworks.sockbowlgame.model.packet.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlgame.model.packet.relationships.ContainsTossup;

import java.util.List;

public class PacketBuilderHelper {

    public static Packet createPacket(String id, String name, Difficulty difficulty, List<ContainsTossup> tossups, List<ContainsBonus> bonuses) {
        Packet packet = new Packet();
        packet.setId(id);
        packet.setName(name);
        packet.setDifficulty(difficulty);
        packet.setTossups(tossups);
        packet.setBonuses(bonuses);
        return packet;
    }

    public static ContainsTossup createTossup(long linkId, int order, Tossup tossup) {
        ContainsTossup packetTossup = new ContainsTossup();
        packetTossup.setId(linkId);
        packetTossup.setTossup(tossup);
        packetTossup.setOrder(order);
        return packetTossup;
    }

    public static ContainsBonus createBonus(long linkId, int order, Bonus bonus) {
        ContainsBonus packetBonus = new ContainsBonus();
        packetBonus.setId(linkId);
        packetBonus.setBonus(bonus);
        packetBonus.setOrder(order);
        return packetBonus;
    }

    public static Difficulty createDifficulty(String id, String name) {
        Difficulty difficulty = new Difficulty();
        difficulty.setId(id);
        difficulty.setName(name);
        return difficulty;
    }

    public static Bonus createBonus(String id, String preamble, Subcategory subcategory) {
        Bonus bonus = new Bonus();
        bonus.setId(id);
        bonus.setPreamble(preamble);
        bonus.setSubcategory(subcategory);
        return bonus;
    }

    public static Subcategory createSubcategory(String id, String name, Category category) {
        Subcategory subcategory = new Subcategory();
        subcategory.setId(id);
        subcategory.setName(name);
        subcategory.setCategory(category);
        return subcategory;
    }

    public static Category createCategory(String id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }
}

