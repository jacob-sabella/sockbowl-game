package com.soulsoftworks.sockbowlgame.judge;

import com.soulsoftworks.sockbowlgame.judge.model.JudgeResult;
import com.soulsoftworks.sockbowlgame.judge.model.ParsedAnswer;
import com.soulsoftworks.sockbowlgame.judge.model.Verdict;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Judges a player's typed answer against a quiz-bowl answer line — the automated
 * "proctor" for single-player mode. Given the raw {@code Tossup.answer} string
 * and a guess, returns ACCEPT / PROMPT / REJECT.
 *
 * <p>Strategy (mirrors qbreader's approach without porting its JS): parse the
 * line, then in order — honor {@code do not accept} directives first (a directed
 * rejection wins), accept exact/alternate/close-fuzzy matches, prompt on
 * explicit {@code prompt on} entries, prompt when the guess is a proper subset
 * of the full answer (last-name-only), else reject. Biased toward PROMPT over a
 * false ACCEPT, since silently crediting a wrong answer erodes trust most.
 */
@Service
public class AnswerJudgeService {

    /** Fuzzy score at/above which a guess is accepted outright. */
    private static final double ACCEPT_THRESHOLD = 0.90;
    /** Fuzzy floor below the threshold at which a phonetic match still accepts. */
    private static final double PHONETIC_FLOOR = 0.80;

    private final AnswerLineParser parser;
    private final AnswerNormalizer normalizer;
    private final FuzzyMatcher matcher;

    public AnswerJudgeService() {
        this(new AnswerLineParser(), new AnswerNormalizer(), new FuzzyMatcher());
    }

    /** For tests / explicit wiring. */
    public AnswerJudgeService(AnswerLineParser parser, AnswerNormalizer normalizer, FuzzyMatcher matcher) {
        this.parser = parser;
        this.normalizer = normalizer;
        this.matcher = matcher;
    }

    public JudgeResult judge(String rawAnswerLine, String playerGuess) {
        String guess = normalizer.normalize(playerGuess);
        if (guess.isEmpty()) {
            return new JudgeResult(Verdict.REJECT, 0.0, "", "empty guess");
        }

        ParsedAnswer parsed = parser.parse(rawAnswerLine);

        // 1. Directed rejection wins first: a guess that matches a "do not accept" entry.
        for (String rejected : parsed.rejected()) {
            Match m = classify(guess, rejected);
            if (m.verdict == Verdict.ACCEPT) {
                return new JudgeResult(Verdict.REJECT, m.confidence, rejected,
                        "matches do-not-accept entry: " + rejected);
            }
        }

        // 2. Explicit "prompt on X" wins over a bare core match: the author flagged X
        //    as needing more specificity. A fuller guess won't exact-match a prompt entry,
        //    so the full answer still falls through to ACCEPT below.
        for (String prompt : parsed.promptable()) {
            Match m = classify(guess, prompt);
            if (m.verdict == Verdict.ACCEPT || m.verdict == Verdict.PROMPT) {
                return new JudgeResult(Verdict.PROMPT, m.confidence, prompt,
                        "prompt-on entry: " + prompt);
            }
        }

        // 3. Accept: core, primary, and explicit alternates.
        Set<String> acceptTargets = new LinkedHashSet<>();
        acceptTargets.add(parsed.core());
        acceptTargets.add(parsed.primary());
        acceptTargets.addAll(parsed.accepted());

        Match bestAccept = null;
        Match bestSubsetPrompt = null;
        for (String target : acceptTargets) {
            Match m = classify(guess, target);
            if (m.verdict == Verdict.ACCEPT && (bestAccept == null || m.confidence > bestAccept.confidence)) {
                bestAccept = m;
            } else if (m.verdict == Verdict.PROMPT && (bestSubsetPrompt == null || m.confidence > bestSubsetPrompt.confidence)) {
                bestSubsetPrompt = m;
            }
        }
        if (bestAccept != null) {
            return new JudgeResult(Verdict.ACCEPT, bestAccept.confidence, bestAccept.target,
                    "matched: " + bestAccept.target);
        }

        // 4. Guess is a proper subset of the full answer (e.g. last name only).
        if (bestSubsetPrompt != null) {
            return new JudgeResult(Verdict.PROMPT, bestSubsetPrompt.confidence, bestSubsetPrompt.target,
                    "partial answer, needs full: " + bestSubsetPrompt.target);
        }

        return new JudgeResult(Verdict.REJECT, 0.0, "", "no match");
    }

    private record Match(Verdict verdict, double confidence, String target) {}

    /** Classifies a normalized guess against one target answer string. */
    private Match classify(String guess, String targetRaw) {
        String target = normalizer.normalize(targetRaw);
        if (target.isEmpty()) {
            return new Match(Verdict.REJECT, 0.0, targetRaw);
        }
        if (guess.equals(target)) {
            return new Match(Verdict.ACCEPT, 1.0, targetRaw);
        }

        Set<String> guessTokens = normalizer.significantTokens(guess);
        Set<String> targetTokens = normalizer.significantTokens(target);
        if (!guessTokens.isEmpty() && targetTokens.containsAll(guessTokens)) {
            if (guessTokens.equals(targetTokens)) {
                // Same content words, different order/stopwords → accept.
                return new Match(Verdict.ACCEPT, 0.95, targetRaw);
            }
            // Proper subset (last name, one word of a phrase) → prompt for the full answer.
            return new Match(Verdict.PROMPT, 0.70, targetRaw);
        }

        double sim = matcher.similarity(guess, target);
        if (sim >= ACCEPT_THRESHOLD) {
            return new Match(Verdict.ACCEPT, sim, targetRaw);
        }
        if (sim >= PHONETIC_FLOOR && matcher.phoneticEquals(guess, target)) {
            return new Match(Verdict.ACCEPT, sim, targetRaw);
        }
        return new Match(Verdict.REJECT, sim, targetRaw);
    }
}
