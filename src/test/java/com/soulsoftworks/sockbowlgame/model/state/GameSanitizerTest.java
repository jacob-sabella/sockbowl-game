package com.soulsoftworks.sockbowlgame.model.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSanitizerTest {

    private static final String TEN_WORD_QUESTION = "one two three four five six seven eight nine ten";
    private static final String TEN_WORD_ANSWER = "the answer";

    private Round buildRound(RoundState roundState, int revealedWordCount) {
        Round round = new Round();
        round.setRoundState(roundState);
        round.setQuestion(TEN_WORD_QUESTION);
        round.setAnswer(TEN_WORD_ANSWER);
        round.setRevealedWordCount(revealedWordCount);
        round.setTotalWordCount(10);
        return round;
    }

    @Test
    void revealQuestionHideAnswerTruncatesForAutoProctorMidRound() {
        Round round = buildRound(RoundState.AWAITING_BUZZ, 3);

        Round result = GameSanitizer.revealQuestionHideAnswer(round, GameMode.AUTO_PROCTOR);

        assertEquals("one two three", result.getQuestion());
        assertEquals("", result.getAnswer());
    }

    @Test
    void revealQuestionHideAnswerKeepsFullTextForSinglePlayer() {
        Round round = buildRound(RoundState.AWAITING_BUZZ, 3);

        Round result = GameSanitizer.revealQuestionHideAnswer(round, GameMode.SINGLE_PLAYER);

        assertEquals(TEN_WORD_QUESTION, result.getQuestion());
        assertEquals("", result.getAnswer());
    }

    @Test
    void revealQuestionHideAnswerKeepsFullTextWhenCompleted() {
        Round round = buildRound(RoundState.COMPLETED, 3);

        Round result = GameSanitizer.revealQuestionHideAnswer(round, GameMode.AUTO_PROCTOR);

        assertEquals(TEN_WORD_QUESTION, result.getQuestion());
    }

    @Test
    void sanitizeGameSessionTruncatesAutoProctorMidRoundForReconnect() {
        Round round = buildRound(RoundState.AWAITING_BUZZ, 2);

        GameSession session = GameSession.builder()
                .id("TEST-SESSION")
                .joinCode("ABCD")
                .gameSettings(GameSettings.builder().gameMode(GameMode.AUTO_PROCTOR).build())
                .build();
        session.getCurrentMatch().setCurrentRound(round);
        session.getCurrentMatch().getPacket().setTossups(java.util.List.of());
        session.getCurrentMatch().getPacket().setBonuses(java.util.List.of());

        GameSession sanitized = GameSanitizer.sanitizeGameSession(session, PlayerMode.BUZZER);

        String question = sanitized.getCurrentMatch().getCurrentRound().getQuestion();
        assertEquals("one two", question);
        assertTrue(!question.isEmpty());
    }

    @Test
    void sanitizeGameSessionStillBlanksClassicMidRound() {
        Round round = buildRound(RoundState.AWAITING_BUZZ, 2);

        GameSession session = GameSession.builder()
                .id("TEST-SESSION")
                .joinCode("ABCD")
                .gameSettings(GameSettings.builder().gameMode(GameMode.QUIZ_BOWL_CLASSIC).build())
                .build();
        session.getCurrentMatch().setCurrentRound(round);
        session.getCurrentMatch().getPacket().setTossups(java.util.List.of());
        session.getCurrentMatch().getPacket().setBonuses(java.util.List.of());

        GameSession sanitized = GameSanitizer.sanitizeGameSession(session, PlayerMode.BUZZER);

        assertEquals("", sanitized.getCurrentMatch().getCurrentRound().getQuestion());
    }

    @Test
    void sanitizeGameSessionReturnsFullQuestionOnceCompleted() {
        Round round = buildRound(RoundState.COMPLETED, 2);

        GameSession session = GameSession.builder()
                .id("TEST-SESSION")
                .joinCode("ABCD")
                .gameSettings(GameSettings.builder().gameMode(GameMode.AUTO_PROCTOR).build())
                .build();
        session.getCurrentMatch().setCurrentRound(round);
        session.getCurrentMatch().getPacket().setTossups(java.util.List.of());
        session.getCurrentMatch().getPacket().setBonuses(java.util.List.of());

        GameSession sanitized = GameSanitizer.sanitizeGameSession(session, PlayerMode.BUZZER);

        assertEquals(TEN_WORD_QUESTION, sanitized.getCurrentMatch().getCurrentRound().getQuestion());
        assertEquals(TEN_WORD_ANSWER, sanitized.getCurrentMatch().getCurrentRound().getAnswer());
    }

    @Test
    void revealQuestionHideAnswerRevealsAnswerDuringBonusPending() {
        Round round = buildRound(RoundState.BONUS_PENDING, 10); // fully revealed by this point

        Round result = GameSanitizer.revealQuestionHideAnswer(round, GameMode.AUTO_PROCTOR);

        assertEquals(TEN_WORD_QUESTION, result.getQuestion()); // full text, no truncation
        assertEquals(TEN_WORD_ANSWER, result.getAnswer());     // tossup answer revealed
    }

    @Test
    void revealQuestionHideAnswerHidesBonusPartAnswersDuringBonusPending() {
        Round round = buildRound(RoundState.BONUS_PENDING, 10);
        com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus =
                com.soulsoftworks.sockbowlquestions.models.nodes.Bonus.builder()
                        .preamble("A three-part bonus.")
                        .bonusParts(java.util.List.of(
                                part(0, "alpha"), part(1, "beta"), part(2, "gamma")))
                        .build();
        round.setCurrentBonus(bonus);

        Round result = GameSanitizer.revealQuestionHideAnswer(round, GameMode.AUTO_PROCTOR);

        result.getCurrentBonus().getBonusParts().forEach(p ->
                assertEquals("", p.getBonusPart().getAnswer()));
    }

    @Test
    void sanitizeGameSessionRevealsTossupAnswerDuringBonusPending() {
        Round round = buildRound(RoundState.BONUS_PENDING, 10);

        GameSession session = GameSession.builder()
                .id("TEST-SESSION")
                .joinCode("ABCD")
                .gameSettings(GameSettings.builder().gameMode(GameMode.AUTO_PROCTOR).build())
                .build();
        session.getCurrentMatch().setCurrentRound(round);
        session.getCurrentMatch().getPacket().setTossups(java.util.List.of());
        session.getCurrentMatch().getPacket().setBonuses(java.util.List.of());

        GameSession sanitized = GameSanitizer.sanitizeGameSession(session, PlayerMode.BUZZER);

        assertEquals(TEN_WORD_ANSWER, sanitized.getCurrentMatch().getCurrentRound().getAnswer());
    }

    @Test
    void sanitizeRoundHidesAssociatedBonusPartAnswers() {
        // associatedBonus is set at round start (Match.advanceRound), so a classic-mode
        // limited-context round update must not carry the upcoming bonus's answers.
        Round round = buildRound(RoundState.AWAITING_BUZZ, 3);
        com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus =
                com.soulsoftworks.sockbowlquestions.models.nodes.Bonus.builder()
                        .preamble("A three-part bonus.")
                        .bonusParts(java.util.List.of(part(0, "alpha"), part(1, "beta"), part(2, "gamma")))
                        .build();
        round.setAssociatedBonus(bonus);

        Round result = GameSanitizer.sanitizeRound(round);

        assertEquals("", result.getQuestion());
        assertEquals("", result.getAnswer());
        result.getAssociatedBonus().getBonusParts().forEach(p ->
                assertEquals("", p.getBonusPart().getAnswer()));
    }

    /* ---------------- identity stripping (AUTH-10) ---------------- */

    /** A session with an authenticated owner, a second signed-in user and a guest, all seated on teams. */
    private GameSession sessionWithIdentities(GameMode mode) {
        GameSession session = GameSession.builder()
                .id("ID-SESSION")
                .joinCode("IDNT")
                .gameOwnerId("kc-host")
                .gameSettings(GameSettings.builder().gameMode(mode).build())
                .build();
        Player host = identified("host", "kc-host", "user-host", PlayerMode.PROCTOR);
        host.setGameOwner(true);
        Player member = identified("member", "kc-member", "user-member", PlayerMode.BUZZER);
        Player guest = Player.builder().playerId("guest").playerSecret("guest-secret")
                .playerMode(PlayerMode.SPECTATOR).build();
        session.getPlayerList().addAll(java.util.List.of(host, member, guest));
        Team team = new Team();
        team.addPlayerToTeam(host);
        team.addPlayerToTeam(member);
        team.addPlayerToTeam(guest);
        session.getTeamList().add(team);
        session.getCurrentMatch().getPacket().setTossups(java.util.List.of());
        session.getCurrentMatch().getPacket().setBonuses(java.util.List.of());
        return session;
    }

    private Player identified(String id, String keycloakId, String userId, PlayerMode mode) {
        return Player.builder().playerId(id).playerSecret(id + "-secret")
                .keycloakId(keycloakId).userId(userId).isGuest(false).playerMode(mode).build();
    }

    private void assertStripped(java.util.List<Player> players) {
        assertFalse(players.isEmpty());
        for (Player p : players) {
            assertTrue(isBlank(p.getKeycloakId()), "keycloakId leaked for " + p.getPlayerId());
            assertTrue(isBlank(p.getUserId()), "userId leaked for " + p.getPlayerId());
            assertTrue(isBlank(p.getPlayerSecret()), "playerSecret leaked for " + p.getPlayerId());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PlayerMode.class)
    void sanitizeGameSessionStripsIdentityForEveryPlayerMode(PlayerMode viewerMode) {
        for (GameMode mode : GameMode.values()) {
            GameSession session = sessionWithIdentities(mode);

            GameSession sanitized = GameSanitizer.sanitizeGameSession(session, viewerMode);

            assertStripped(sanitized.getPlayerList());
            assertStripped(sanitized.getTeamList().get(0).getTeamPlayers());
            assertTrue(isBlank(sanitized.getGameOwnerId()), "gameOwnerId leaked in " + mode);
            // ng still needs to know who owns the session.
            assertTrue(sanitized.getPlayerById("host").isGameOwner());
            assertFalse(sanitized.getPlayerById("member").isGameOwner());
        }
    }

    @Test
    void sanitizeGameSessionDoesNotModifyTheOriginal() {
        GameSession session = sessionWithIdentities(GameMode.QUIZ_BOWL_CLASSIC);

        GameSanitizer.sanitizeGameSession(session, PlayerMode.BUZZER);

        assertEquals("kc-host", session.getGameOwnerId());
        assertEquals("kc-host", session.getPlayerById("host").getKeycloakId());
        assertEquals("user-host", session.getPlayerById("host").getUserId());
        assertEquals("host-secret", session.getPlayerById("host").getPlayerSecret());
        assertEquals("kc-member", session.getTeamList().get(0).getTeamPlayers().get(1).getKeycloakId());
    }

    @Test
    void sanitizePlayerListStripsIdentityAndSecret() {
        GameSession session = sessionWithIdentities(GameMode.QUIZ_BOWL_CLASSIC);

        java.util.List<Player> sanitized = GameSanitizer.sanitizePlayerList(session.getPlayerList());

        assertStripped(sanitized);
        assertTrue(sanitized.get(0).isGameOwner());
        assertEquals("kc-host", session.getPlayerList().get(0).getKeycloakId(), "original untouched");
    }

    @Test
    void sanitizeTeamListStripsIdentityAndSecretOfTeamPlayers() {
        GameSession session = sessionWithIdentities(GameMode.QUIZ_BOWL_CLASSIC);

        java.util.List<Team> sanitized = GameSanitizer.sanitizeTeamList(session.getTeamList());

        assertStripped(sanitized.get(0).getTeamPlayers());
        assertEquals(session.getTeamList().get(0).getTeamId(), sanitized.get(0).getTeamId());
        assertEquals("guest-secret", session.getTeamList().get(0).getTeamPlayers().get(2).getPlayerSecret(),
                "original untouched");
    }

    @Test
    void playerRosterUpdateCarriesNoIdentityInPlayersOrTeams() {
        GameSession session = sessionWithIdentities(GameMode.QUIZ_BOWL_CLASSIC);

        com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate update =
                com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate.fromGameSession(session);

        assertStripped(update.getPlayerList());
        assertStripped(update.getTeamList().get(0).getTeamPlayers());
    }

    private com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart part(int order, String answer) {
        return com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart.builder()
                .order(order)
                .bonusPart(com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart.builder()
                        .question("q" + order).answer(answer).build())
                .build();
    }
}
