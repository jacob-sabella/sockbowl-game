package com.soulsoftworks.sockbowlgame.service.packet;

/**
 * Binds each EPHEMERAL packet to the one game that first loaded it (R3-G-01).
 *
 * <p>An EPHEMERAL packet is generated for one game (D15) and is readable only
 * by the game service's own token, so its answers reach a player only through
 * the proctor view of a game that loaded it. Without a binding, any player who
 * learned its id could create a second game, take that game's proctor seat,
 * load the packet there and read every answer while the first game plays it.
 * The first successful SetMatchPacket of an EPHEMERAL packet therefore binds
 * the packet to that game, and every other game is refused.
 */
public interface EphemeralPacketBindings {

    /**
     * Bind {@code packetId} to {@code gameSessionId} if it is not bound yet,
     * atomically.
     *
     * @return true when the packet is now bound to this game (newly, or by an
     *         earlier load in the same game); false when another game holds it
     * @throws RuntimeException when the binding store is unreachable; the
     *         caller must then refuse the load (fail closed)
     */
    boolean bindToGame(String packetId, String gameSessionId);
}
