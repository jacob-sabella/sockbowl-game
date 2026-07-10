package com.soulsoftworks.sockbowlgame.model.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart part(int order, String answer) {
        return com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart.builder()
                .order(order)
                .bonusPart(com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart.builder()
                        .question("q" + order).answer(answer).build())
                .build();
    }
}
