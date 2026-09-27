package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Creator;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Room;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.soulsoftworks.sockbowlgame.support.StompMappingClassification.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In-session role checks with auth on. In a proctored game where the
 * session owner is <em>not</em> the proctor:
 * <ul>
 *   <li>every {@link com.soulsoftworks.sockbowlgame.support.StompMappingClassification#PROCTOR_GATED}
 *       destination from a non-proctor (the owner included) is a "Permission
 *       Denied" {@code ProcessError}, and the proctor passes the role gate;</li>
 *   <li>every owner-gated destination whose gate is the proctor in proctored
 *       modes ({@code PROCTOR_IN_PROCTORED_MODES}) likewise rejects the owner
 *       and admits the proctor.</li>
 * </ul>
 * The proctor's messages may still fail game-state checks (no match running,
 * nothing to judge); only the authorization outcome is asserted for them.
 */
class InSessionRoleMatrixTest {

    @Test
    void classificationSetsAreDisjointAndConsistent() {
        List<java.util.Set<String>> sets = List.of(OWNER_GATED, PROCTOR_GATED, ANY_PLAYER);
        int total = sets.stream().mapToInt(java.util.Set::size).sum();
        assertEquals(total, ALL.size(), "a destination is in more than one class");
        assertTrue(OWNER_GATED.containsAll(PROCTOR_IN_PROCTORED_MODES));
        for (String destination : ALL) {
            assertTrue(destination.startsWith(APP_PREFIX + "/"), destination);
            if (!destination.equals(HEARTBEAT)) {
                assertNotNull(MESSAGE_TYPES.get(destination), "no message type for " + destination);
            }
        }
        assertEquals(ALL.size() - 1, MESSAGE_TYPES.size());
    }

    @TestFactory
    Stream<DynamicTest> proctorOnlyDestinationsRejectNonProctors() {
        return matrix(new TreeSet<>(PROCTOR_GATED));
    }

    @TestFactory
    Stream<DynamicTest> proctoredModesGateOwnerActionsOnTheProctor() {
        return matrix(new TreeSet<>(PROCTOR_IN_PROCTORED_MODES));
    }

    private Stream<DynamicTest> matrix(Iterable<String> destinations) {
        List<DynamicTest> tests = new ArrayList<>();
        for (String destination : destinations) {
            for (Creator creator : Creator.values()) {
                tests.add(DynamicTest.dynamicTest(destination + " [" + creator + "] proctor admitted", () -> {
                    InSessionFixture fx = new InSessionFixture();
                    Room room = proctoredRoom(fx, creator);
                    SockbowlOutMessage out = fx.dispatch(
                            InSessionFixture.message(destination, room.session(), room.teammate(), null));
                    assertFalse(InSessionFixture.isAccessDenied(out), "proctor denied: " + out);
                }));
                for (String nonProctor : nonProctors(creator)) {
                    tests.add(DynamicTest.dynamicTest(destination + " [" + creator + "] " + nonProctor + " denied", () -> {
                        InSessionFixture fx = new InSessionFixture();
                        Room room = proctoredRoom(fx, creator);
                        GameSession session = room.session();
                        SockbowlOutMessage out = fx.dispatch(
                                InSessionFixture.message(destination, session, nonProctor, null));
                        assertTrue(InSessionFixture.isAccessDenied(out), "expected Permission Denied, got " + out);
                    }));
                }
            }
        }
        return tests.stream();
    }

    /** Everyone except the proctor: the owner first, then the other players. */
    private static List<String> nonProctors(Creator creator) {
        return creator == Creator.AUTHENTICATED
                ? List.of("host", "guest", "other")
                : List.of("first", "second");
    }

    /** A QUIZ_BOWL_CLASSIC room with a packet selected and the (non-owner) teammate as proctor. */
    private static Room proctoredRoom(InSessionFixture fx, Creator creator) {
        Room room = fx.room(creator, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.selectPacket(room.session());
        InSessionFixture.makeProctor(room.session(), room.teammate());
        assertTrue(fx.policy.isSessionOwner(room.session(), room.owner()));
        assertFalse(fx.policy.isSessionOwner(room.session(), room.teammate()));
        return room;
    }
}
