package com.soulsoftworks.sockbowlgame.support;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.GetGameState;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdateGameSettings;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AnswerOutcome;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.BonusPartOutcome;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.FinishedReading;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.FinishedReadingBonusPart;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.FinishedReadingBonusPreamble;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.StartBonus;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutBonusPart;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutRound;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.EndMatch;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The authorization class of every inbound STOMP {@code @MessageMapping}
 * destination (M2 WP-G3). This is the single list the in-session authorization
 * tests iterate, and the list the mapping inventory test checks for
 * completeness, so a new {@code @MessageMapping} that nobody classifies fails
 * the build.
 *
 * <ul>
 *   <li>{@link #OWNER_GATED}: only the session owner may send it (in
 *       proctorless modes; the subset in {@link #PROCTOR_IN_PROCTORED_MODES}
 *       is the proctor's in proctored modes). For {@code update-player-team}
 *       and {@code set-proctor} this is "for another player": a player may
 *       still move themselves, or claim an empty proctor seat.</li>
 *   <li>{@link #PROCTOR_GATED}: only the current proctor, in every mode.</li>
 *   <li>{@link #ANY_PLAYER}: any connected player in the session (buzzing,
 *       answering and reading state are then checked by game rules, not by
 *       role).</li>
 * </ul>
 *
 * Every destination is in exactly one of the three sets. Destinations are the
 * full client-side SEND destinations, including the {@code /app} prefix.
 */
public final class StompMappingClassification {

    private StompMappingClassification() {}

    public static final String APP_PREFIX = "/app";

    // /app/game/config/**
    public static final String UPDATE_PLAYER_TEAM = "/app/game/config/update-player-team";
    public static final String SET_MATCH_PACKET = "/app/game/config/set-match-packet";
    public static final String SET_PROCTOR = "/app/game/config/set-proctor";
    public static final String GET_GAME = "/app/game/config/get-game";
    public static final String UPDATE_GAME_SETTINGS = "/app/game/config/update-game-settings";

    // /app/game/progression/**
    public static final String START_MATCH = "/app/game/progression/start-match";
    public static final String END_MATCH = "/app/game/progression/end-match";

    // /app/game/**
    public static final String ANSWER_OUTCOME = "/app/game/answer-outcome";
    public static final String PLAYER_INCOMING_BUZZ = "/app/game/player-incoming-buzz";
    public static final String SUBMIT_ANSWER = "/app/game/submit-answer";
    public static final String TIMEOUT_ROUND = "/app/game/timeout-round";
    public static final String FINISHED_READING = "/app/game/finished-reading";
    public static final String ADVANCE_ROUND = "/app/game/advance-round";
    public static final String BONUS_PART_OUTCOME = "/app/game/bonus-part-outcome";
    public static final String FINISHED_READING_BONUS_PREAMBLE = "/app/game/finished-reading-bonus-preamble";
    public static final String FINISHED_READING_BONUS_PART = "/app/game/finished-reading-bonus-part";
    public static final String TIMEOUT_BONUS_PART = "/app/game/timeout-bonus-part";
    public static final String START_BONUS = "/app/game/start-bonus";

    // Not a game message: answered directly by HeartbeatController, never reaches a processor.
    public static final String HEARTBEAT = "/app/heartbeat";

    /** Session-owner only (proctorless modes), or the owner acting on another player. */
    public static final Set<String> OWNER_GATED = Set.of(
            START_MATCH,
            END_MATCH,
            ADVANCE_ROUND,
            SET_MATCH_PACKET,
            UPDATE_GAME_SETTINGS,
            TIMEOUT_ROUND,
            START_BONUS,
            UPDATE_PLAYER_TEAM,
            SET_PROCTOR);

    /**
     * The owner-gated destinations whose gate is the proctor, not the owner,
     * when the game mode has a human proctor.
     */
    public static final Set<String> PROCTOR_IN_PROCTORED_MODES = Set.of(
            START_MATCH,
            END_MATCH,
            ADVANCE_ROUND,
            SET_MATCH_PACKET,
            UPDATE_GAME_SETTINGS,
            TIMEOUT_ROUND);

    /**
     * Current-proctor only, in every mode. {@code timeout-bonus-part} is here
     * rather than in {@link #OWNER_GATED}: the processor has no proctorless
     * owner branch for it.
     */
    public static final Set<String> PROCTOR_GATED = Set.of(
            ANSWER_OUTCOME,
            BONUS_PART_OUTCOME,
            FINISHED_READING,
            FINISHED_READING_BONUS_PREAMBLE,
            FINISHED_READING_BONUS_PART,
            TIMEOUT_BONUS_PART);

    /** Any player in the session. */
    public static final Set<String> ANY_PLAYER = Set.of(
            PLAYER_INCOMING_BUZZ,
            SUBMIT_ANSWER,
            GET_GAME,
            HEARTBEAT);

    /** Every classified destination. */
    public static final Set<String> ALL;

    /**
     * The inbound message type each game destination produces (every
     * destination except {@link #HEARTBEAT}, which has no message type).
     */
    public static final Map<String, Class<? extends SockbowlInMessage>> MESSAGE_TYPES;

    static {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(OWNER_GATED);
        all.addAll(PROCTOR_GATED);
        all.addAll(ANY_PLAYER);
        ALL = Set.copyOf(all);

        Map<String, Class<? extends SockbowlInMessage>> types = new LinkedHashMap<>();
        types.put(UPDATE_PLAYER_TEAM, UpdatePlayerTeam.class);
        types.put(SET_MATCH_PACKET, SetMatchPacket.class);
        types.put(SET_PROCTOR, SetProctor.class);
        types.put(GET_GAME, GetGameState.class);
        types.put(UPDATE_GAME_SETTINGS, UpdateGameSettings.class);
        types.put(START_MATCH, StartMatch.class);
        types.put(END_MATCH, EndMatch.class);
        types.put(ANSWER_OUTCOME, AnswerOutcome.class);
        types.put(PLAYER_INCOMING_BUZZ, PlayerIncomingBuzz.class);
        types.put(SUBMIT_ANSWER, SubmitAnswer.class);
        types.put(TIMEOUT_ROUND, TimeoutRound.class);
        types.put(FINISHED_READING, FinishedReading.class);
        types.put(ADVANCE_ROUND, AdvanceRound.class);
        types.put(BONUS_PART_OUTCOME, BonusPartOutcome.class);
        types.put(FINISHED_READING_BONUS_PREAMBLE, FinishedReadingBonusPreamble.class);
        types.put(FINISHED_READING_BONUS_PART, FinishedReadingBonusPart.class);
        types.put(TIMEOUT_BONUS_PART, TimeoutBonusPart.class);
        types.put(START_BONUS, StartBonus.class);
        MESSAGE_TYPES = Map.copyOf(types);
    }

    /** The class a destination belongs to, or null if it is unclassified. */
    public static String classOf(String destination) {
        if (OWNER_GATED.contains(destination)) {
            return "OWNER_GATED";
        }
        if (PROCTOR_GATED.contains(destination)) {
            return "PROCTOR_GATED";
        }
        if (ANY_PLAYER.contains(destination)) {
            return "ANY_PLAYER";
        }
        return null;
    }
}
