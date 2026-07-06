package com.soulsoftworks.sockbowlgame.judge;

import com.soulsoftworks.sockbowlgame.judge.model.JudgeResult;
import com.soulsoftworks.sockbowlgame.judge.model.Verdict;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Table-driven behavior tests for the single-player answer judge. */
class AnswerJudgeServiceTest {

    private final AnswerJudgeService judge = new AnswerJudgeService();

    static Stream<Arguments> cases() {
        String napoleon = "<b><u>Napoleon</u></b> Bonaparte";
        String unitedStates = "<u>United States</u> [accept USA or America]";
        String madison = "<u>James Madison</u> [prompt on Madison]";
        String iron = "<u>iron</u> [do not accept steel]";
        return Stream.of(
                // exact core / primary
                Arguments.of("exact core", napoleon, "Napoleon", Verdict.ACCEPT),
                Arguments.of("exact primary", napoleon, "napoleon bonaparte", Verdict.ACCEPT),
                // fuzzy typo
                Arguments.of("typo", napoleon, "napolean", Verdict.ACCEPT),
                // wrong answer
                Arguments.of("wrong person", napoleon, "hitler", Verdict.REJECT),
                // diacritics + case
                Arguments.of("diacritic fold", "<u>Beyoncé</u>", "beyonce", Verdict.ACCEPT),
                // accepted alternates
                Arguments.of("accept alt usa", unitedStates, "USA", Verdict.ACCEPT),
                Arguments.of("accept alt america", unitedStates, "america", Verdict.ACCEPT),
                Arguments.of("reject non-alt", unitedStates, "canada", Verdict.REJECT),
                // prompt directive
                Arguments.of("prompt on last name", madison, "Madison", Verdict.PROMPT),
                Arguments.of("full name accepts", madison, "James Madison", Verdict.ACCEPT),
                // do-not-accept directive wins
                Arguments.of("directed reject", iron, "steel", Verdict.REJECT),
                Arguments.of("core still accepts", iron, "iron", Verdict.ACCEPT),
                // word order (order-insensitive content match)
                Arguments.of("word order", "<u>Sea of Tranquility</u>", "tranquility sea", Verdict.ACCEPT),
                // parenthetical clarification ignored
                Arguments.of("clarification ignored", "<u>pi</u> (the number)", "pi", Verdict.ACCEPT),
                // partial answer → prompt for full
                Arguments.of("last-name subset prompts", "<u>George Washington Carver</u>", "carver", Verdict.PROMPT),
                // over-accept guard
                Arguments.of("over-accept guard", "<u>Mercury</u>", "Venus", Verdict.REJECT),
                // leading-article fold
                Arguments.of("article fold", "<u>The Great Gatsby</u>", "great gatsby", Verdict.ACCEPT),
                // empty guess
                Arguments.of("empty guess", napoleon, "   ", Verdict.REJECT),

                // --- regression: multiple directives in one bracket (';'-separated) ---
                Arguments.of("multi-directive reject",
                        "<b><u>Beijing</u></b> [accept Peking; do not accept \"Nanjing\"]", "Nanjing", Verdict.REJECT),
                Arguments.of("multi-directive accept-alt",
                        "<b><u>Beijing</u></b> [accept Peking; do not accept \"Nanjing\"]", "Peking", Verdict.ACCEPT),
                Arguments.of("multi-directive prompt+reject → reject",
                        "<b>Battle of <u>Gettysburg</u></b> [prompt on \"Pennsylvania\"; do not accept \"Antietam\"]",
                        "Antietam", Verdict.REJECT),
                Arguments.of("multi-directive prompt+reject → prompt",
                        "<b>Battle of <u>Gettysburg</u></b> [prompt on \"Pennsylvania\"; do not accept \"Antietam\"]",
                        "Pennsylvania", Verdict.PROMPT),
                // --- regression: explicit "prompt on <core>" beats the bare core match ---
                Arguments.of("prompt-on-core prompts",
                        "<b>Marie <u>Curie</u></b> [prompt on \"Curie\" alone; do not accept \"Pierre Curie\"]",
                        "Curie", Verdict.PROMPT),
                Arguments.of("prompt-on-core full name accepts",
                        "<b>Marie <u>Curie</u></b> [prompt on \"Curie\" alone; do not accept \"Pierre Curie\"]",
                        "Marie Curie", Verdict.ACCEPT),
                Arguments.of("prompt-on-core related person rejects",
                        "<b>Marie <u>Curie</u></b> [prompt on \"Curie\" alone; do not accept \"Pierre Curie\"]",
                        "Pierre Curie", Verdict.REJECT)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void judges(String name, String answerLine, String guess, Verdict expected) {
        JudgeResult result = judge.judge(answerLine, guess);
        assertEquals(expected, result.verdict(),
                () -> name + " — guess='" + guess + "' vs '" + answerLine + "' → " + result.reason()
                        + " (conf=" + result.confidence() + ")");
    }
}
