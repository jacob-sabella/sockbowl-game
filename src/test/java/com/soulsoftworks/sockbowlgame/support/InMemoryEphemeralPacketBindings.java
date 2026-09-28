package com.soulsoftworks.sockbowlgame.support;

import com.soulsoftworks.sockbowlgame.service.packet.EphemeralPacketBindings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@link EphemeralPacketBindings} for processor tests: the same SET NX semantics, in a map, with no expiry. */
public class InMemoryEphemeralPacketBindings implements EphemeralPacketBindings {

    private final Map<String, String> gameByPacket = new ConcurrentHashMap<>();

    @Override
    public boolean bindToGame(String packetId, String gameSessionId) {
        if (packetId == null || packetId.isBlank() || gameSessionId == null || gameSessionId.isBlank()) {
            return false;
        }
        return gameSessionId.equals(gameByPacket.putIfAbsent(packetId, gameSessionId))
                || gameSessionId.equals(gameByPacket.get(packetId));
    }

    /** The game the packet is bound to, or null. */
    public String boundGame(String packetId) {
        return gameByPacket.get(packetId);
    }
}
