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

    /** Joins the first {@code wordCount} tokens back into a plain-text string. */
    public static String truncate(String questionHtml, int wordCount) {
        List<String> tokens = tokenize(questionHtml);
        int n = Math.max(0, Math.min(wordCount, tokens.size()));
        return String.join(" ", tokens.subList(0, n));
    }
}
