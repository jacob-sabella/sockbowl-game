package com.soulsoftworks.sockbowlgame.model.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for a live bug: mid-bonus, clients showed the preamble but an EMPTY
 * bonus part question. Pushes a bonus round through the same pipeline the wire
 * uses: sanitizer (Gson deep copy) then Jackson serialization, and asserts the
 * part questions survive end to end.
 */
class BonusWirePipelineTest {

    @Test
    void bonusPartQuestionsSurviveSanitizerThenJackson() throws Exception {
        Round round = new Round();
        round.setRoundState(RoundState.BONUS_AWAITING_ANSWER);
        round.setQuestion("tossup question text here");
        round.setAnswer("tossup answer");
        round.setRevealedWordCount(4);
        round.setTotalWordCount(4);
        Bonus bonus = Bonus.builder()
                .preamble("For 10 points each:")
                .bonusParts(List.of(
                        new HasBonusPart(0, BonusPart.builder().question("part zero q").answer("a0").build()),
                        new HasBonusPart(1, BonusPart.builder().question("part one q").answer("a1").build()),
                        new HasBonusPart(2, BonusPart.builder().question("part two q").answer("a2").build())))
                .build();
        round.setCurrentBonus(bonus);

        // Hop 1: sanitizer (Gson deep copy + answer blanking + truncation gates)
        Round sanitized = GameSanitizer.revealQuestionHideAnswer(round, GameMode.FREE_FOR_ALL);
        assertNotNull(sanitized.getCurrentBonus(), "currentBonus lost in sanitizer");
        assertNotNull(sanitized.getCurrentBonus().getBonusParts(), "bonusParts lost in sanitizer");
        assertEquals(3, sanitized.getCurrentBonus().getBonusParts().size());
        sanitized.getCurrentBonus().getBonusParts().forEach(p -> {
            assertNotNull(p.getOrder(), "part order lost in sanitizer");
            assertTrue(p.getBonusPart().getQuestion().startsWith("part"), "part question lost in sanitizer");
            assertEquals("", p.getBonusPart().getAnswer(), "part answer must be blanked");
        });

        // Hop 2: Jackson (what the websocket layer serializes)
        String json = new ObjectMapper().writeValueAsString(sanitized);
        JsonNode parts = new ObjectMapper().readTree(json).path("currentBonus").path("bonusParts");
        assertTrue(parts.isArray(), "bonusParts missing from wire JSON: " + json);
        assertEquals(3, parts.size());
        for (JsonNode p : parts) {
            assertNotNull(p.path("order").asText(null), "order missing on wire");
            assertTrue(p.path("bonusPart").path("question").asText("").startsWith("part"),
                    "part question missing on wire: " + p);
        }
    }
}
