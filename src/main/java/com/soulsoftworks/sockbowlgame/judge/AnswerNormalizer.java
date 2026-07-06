package com.soulsoftworks.sockbowlgame.judge;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonicalizes answer strings for comparison: case, diacritics, punctuation,
 * leading articles, and whitespace are all folded away so "Beyoncé" and
 * "beyonce" (and "The Beatles" / "beatles") compare equal.
 *
 * <p>Pure and stateless — no Spring, trivially unit-testable.
 */
public final class AnswerNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(a|an|the)\\s+");

    /** Stopwords dropped when comparing significant word content (order-insensitive). */
    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "of", "and");

    /** Fully canonical form used for direct string comparison. */
    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD);
        s = DIACRITICS.matcher(s).replaceAll("");
        s = s.toLowerCase();
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        // Strip a single leading article (repeat to be safe on "the a ..." oddities).
        String prev;
        do {
            prev = s;
            s = LEADING_ARTICLE.matcher(s).replaceFirst("");
        } while (!s.equals(prev));
        return s;
    }

    /** Content tokens of a normalized string, excluding stopwords. Order-insensitive. */
    public Set<String> significantTokens(String raw) {
        String norm = normalize(raw);
        Set<String> tokens = new LinkedHashSet<>();
        if (norm.isEmpty()) {
            return tokens;
        }
        Arrays.stream(norm.split(" "))
                .filter(t -> !t.isEmpty() && !STOPWORDS.contains(t))
                .forEach(tokens::add);
        return tokens;
    }
}
