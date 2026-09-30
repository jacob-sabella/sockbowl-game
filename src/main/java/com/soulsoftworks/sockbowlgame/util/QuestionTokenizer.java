package com.soulsoftworks.sockbowlgame.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stateless HTML-strip + whitespace tokenizer used to drive the server-authoritative
 * word-by-word reveal for AUTO_PROCTOR rounds. Never cache the token list on a
 * persisted model — retokenizing a short question string is cheap; recompute on demand.
 */
public final class QuestionTokenizer {

    private QuestionTokenizer() {}

    /** Strips HTML tags, collapses whitespace, splits into words. */
    public static List<String> tokenize(String questionHtml) {
        if (questionHtml == null || questionHtml.isBlank()) {
            return Collections.emptyList();
        }
        String stripped = questionHtml.replaceAll("<[^>]*>", " ").trim();
        if (stripped.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(stripped.split("\\s+"));
    }

    /**
     * How long a word takes to read relative to an average word (about 1.0), so the reveal
     * sounds like a person reading: longer words take longer, and there's a beat after a
     * comma and a longer one at the end of a sentence. The client paces its display with
     * the same weights (reading-cadence.ts), so keep the two in step.
     */
    public static double wordWeight(String word) {
        if (word == null || word.isEmpty()) {
            return 0.75;
        }
        int letters = 0;
        for (int i = 0; i < word.length(); i++) {
            if (Character.isLetterOrDigit(word.charAt(i))) {
                letters++;
            }
        }
        double weight = 0.75 + Math.min(0.6, 0.06 * Math.max(0, letters - 4));
        String end = word.replaceAll("[\"'\u201D\u2019)\\]]+$", "");
        if (end.endsWith(".") || end.endsWith("?") || end.endsWith("!")) {
            weight += 1.0;
        } else if (end.endsWith(",") || end.endsWith(";") || end.endsWith(":")) {
            weight += 0.5;
        }
        return weight;
    }

    /** Joins the first {@code wordCount} tokens back into a plain-text string. */
    public static String truncate(String questionHtml, int wordCount) {
        List<String> tokens = tokenize(questionHtml);
        int n = Math.max(0, Math.min(wordCount, tokens.size()));
        return String.join(" ", tokens.subList(0, n));
    }
}
