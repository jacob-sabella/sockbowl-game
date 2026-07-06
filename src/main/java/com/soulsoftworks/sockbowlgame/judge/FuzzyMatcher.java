package com.soulsoftworks.sockbowlgame.judge;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.text.similarity.LevenshteinDistance;

/**
 * Scores how close two already-normalized strings are, combining edit-distance
 * (Levenshtein), prefix-weighted similarity (Jaro-Winkler), and English phonetics
 * (Double Metaphone). Tuned to catch typos and spelling variants without
 * over-accepting genuinely different answers.
 *
 * <p>Pure and stateless.
 */
public final class FuzzyMatcher {

    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();
    private final LevenshteinDistance levenshtein = new LevenshteinDistance();
    private final DoubleMetaphone metaphone = new DoubleMetaphone();

    /** Similarity of two normalized strings, 0.0–1.0. */
    public double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        double jw = jaroWinkler.apply(a, b);
        int dist = levenshtein.apply(a, b);
        int maxLen = Math.max(a.length(), b.length());
        double levRatio = maxLen == 0 ? 0.0 : 1.0 - ((double) dist / maxLen);
        return Math.max(jw, levRatio);
    }

    /** True when two strings encode to the same English phonetic key (both non-empty). */
    public boolean phoneticEquals(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String ea = metaphone.doubleMetaphone(a);
        String eb = metaphone.doubleMetaphone(b);
        return ea != null && !ea.isEmpty() && ea.equals(eb);
    }
}
