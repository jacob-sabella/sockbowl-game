package com.soulsoftworks.sockbowlgame.judge;

import com.soulsoftworks.sockbowlgame.judge.model.ParsedAnswer;
import org.apache.commons.text.StringEscapeUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw quiz-bowl answer line into a {@link ParsedAnswer}.
 *
 * <p>Answer lines carry HTML (often {@code <b><u>...</u></b>} around the core),
 * bracketed alternates ({@code [accept USA or America]}), parentheticals
 * ({@code (the number)}), and prose directives ({@code prompt on X},
 * {@code do not accept Y}). This parser is defensive: it copes with missing
 * markup and treats an unclassified bracket as an acceptable alternate (never a
 * reject), since silently rejecting a right answer is the worse failure.
 *
 * <p>Pure and stateless.
 */
public final class AnswerLineParser {

    private static final Pattern UNDERLINE = Pattern.compile("<u\\b[^>]*>(.*?)</u>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ANSWER_PREFIX = Pattern.compile("^\\s*answers?\\s*:?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN = Pattern.compile("\\(([^)]*)\\)");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");
    /** Splits alternates within ONE directive: " or ", ",". (";" separates directives, handled first.) */
    private static final Pattern ALT_SPLIT = Pattern.compile("\\s+or\\s+|,");

    public ParsedAnswer parse(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return new ParsedAnswer("", "", Set.of(), Set.of(), Set.of(), List.of(), "");
        }

        String unescaped = StringEscapeUtils.unescapeHtml4(rawAnswer);

        // Core = text inside the first <u>…</u> (before tags are stripped).
        String core = "";
        Matcher u = UNDERLINE.matcher(unescaped);
        if (u.find()) {
            core = stripTags(u.group(1)).trim();
        }

        String plain = stripTags(unescaped);
        plain = ANSWER_PREFIX.matcher(plain).replaceFirst("").trim();

        // Pull parentheticals out as clarifications (ignored for matching).
        List<String> clarifications = new ArrayList<>();
        Matcher p = PAREN.matcher(plain);
        while (p.find()) {
            String c = p.group(1).trim();
            if (!c.isEmpty()) {
                clarifications.add(c);
            }
        }
        String display = plain.trim();
        String working = PAREN.matcher(plain).replaceAll(" ").trim();

        Set<String> accepted = new LinkedHashSet<>();
        Set<String> promptable = new LinkedHashSet<>();
        Set<String> rejected = new LinkedHashSet<>();

        // Bracketed clauses hold directives; classify each and remove from head.
        Matcher b = BRACKET.matcher(working);
        StringBuilder headBuf = new StringBuilder();
        int last = 0;
        while (b.find()) {
            headBuf.append(working, last, b.start());
            classifyClause(b.group(1), accepted, promptable, rejected);
            last = b.end();
        }
        headBuf.append(working.substring(last));
        String head = headBuf.toString();

        // Any trailing prose directive not in brackets (e.g. "iron; prompt on metal").
        String primary = extractTrailingDirectives(head, accepted, promptable, rejected).trim();

        if (core.isEmpty()) {
            core = primary;
        }
        // The head answer and its core are always acceptable.
        if (!primary.isEmpty()) {
            accepted.add(primary);
        }
        if (!core.isEmpty()) {
            accepted.add(core);
        }
        if (display.isEmpty()) {
            display = primary;
        }

        return new ParsedAnswer(primary, core, accepted, promptable, rejected, clarifications, display);
    }

    /** Splits the head on the first directive keyword, routing the tail to the right bucket. */
    private String extractTrailingDirectives(String head, Set<String> accepted, Set<String> promptable, Set<String> rejected) {
        String lower = head.toLowerCase();
        int cut = head.length();
        for (String kw : new String[]{"do not accept", "do not prompt", "prompt on", "prompt", "also accept", "accept", "reject"}) {
            int idx = indexOfKeyword(lower, kw);
            if (idx >= 0 && idx < cut) {
                cut = idx;
            }
        }
        if (cut == head.length()) {
            return head;
        }
        String primary = head.substring(0, cut);
        classifyClause(head.substring(cut), accepted, promptable, rejected);
        return primary;
    }

    /** A bracket/prose clause may hold several ';'-separated directives — classify each on its own. */
    private void classifyClause(String clauseRaw, Set<String> accepted, Set<String> promptable, Set<String> rejected) {
        for (String directive : clauseRaw.split(";")) {
            classifyDirective(directive, accepted, promptable, rejected);
        }
    }

    private void classifyDirective(String clauseRaw, Set<String> accepted, Set<String> promptable, Set<String> rejected) {
        String clause = clauseRaw.trim();
        if (clause.isEmpty()) {
            return;
        }
        String lower = clause.toLowerCase();
        Set<String> bucket;
        String body;
        if (startsWithKeyword(lower, "do not accept") || startsWithKeyword(lower, "do not prompt") || startsWithKeyword(lower, "reject")) {
            bucket = rejected;
            body = stripKeyword(clause, "do not accept", "do not prompt", "reject");
        } else if (startsWithKeyword(lower, "prompt on") || startsWithKeyword(lower, "prompt")) {
            bucket = promptable;
            body = stripKeyword(clause, "prompt on", "prompt");
        } else if (startsWithKeyword(lower, "also accept") || startsWithKeyword(lower, "accept") || startsWithKeyword(lower, "or")) {
            bucket = accepted;
            body = stripKeyword(clause, "also accept", "accept", "or");
        } else {
            // Unclassified bracket → treat as an acceptable alternate (safe default).
            bucket = accepted;
            body = clause;
        }
        for (String alt : ALT_SPLIT.split(body)) {
            String a = alt.trim();
            if (!a.isEmpty()) {
                bucket.add(a);
            }
        }
    }

    private static String stripKeyword(String clause, String... keywords) {
        String lower = clause.toLowerCase();
        for (String kw : keywords) {
            if (startsWithKeyword(lower, kw)) {
                return clause.substring(kw.length()).trim();
            }
        }
        return clause;
    }

    /**
     * True if {@code lower} begins with {@code kw} as a whole word — i.e. {@code kw}
     * is followed by end-of-string or a non-alphanumeric char. Prevents a bare
     * alternate that merely starts with a directive keyword ("Oregon", "orange",
     * "acceptance") from being misread as a directive and having its leading letters
     * stripped ("egon", "ange", "ance"), which would drop the real answer from the
     * accepted set and reject a correct guess.
     */
    private static boolean startsWithKeyword(String lower, String kw) {
        if (!lower.startsWith(kw)) {
            return false;
        }
        int after = kw.length();
        return after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
    }

    /** Word-boundary-ish keyword search so "prompt" doesn't match inside a word. */
    private static int indexOfKeyword(String lower, String kw) {
        int from = 0;
        while (true) {
            int idx = lower.indexOf(kw, from);
            if (idx < 0) {
                return -1;
            }
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            int after = idx + kw.length();
            boolean rightOk = after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
            if (leftOk && rightOk) {
                return idx;
            }
            from = idx + 1;
        }
    }

    private static String stripTags(String s) {
        return HTML_TAG.matcher(s).replaceAll("");
    }
}
