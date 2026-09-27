package com.soulsoftworks.sockbowlgame.client;

/**
 * sockbowl-questions answered, and has no packet with this id that the game
 * service may read ({@code getPacketById} returned null).
 */
public class PacketNotFoundException extends RuntimeException {

    private final String packetId;

    public PacketNotFoundException(String packetId) {
        super("Packet " + packetId + " not found");
        this.packetId = packetId;
    }

    public String getPacketId() {
        return packetId;
    }
}
