package com.soulsoftworks.sockbowlgame.judge.model;

import java.util.List;
import java.util.Set;

/**
 * A quiz-bowl answer line ({@code Tossup.answer} is one raw markup string) parsed
 * into its judgeable parts.
 *
 * @param primary        the full head answer, plain text (e.g. "Napoleon Bonaparte")
 * @param core           the underlined/bolded required portion (e.g. "Napoleon");
 *                       equals {@code primary} when no markup is present
 * @param accepted       explicit acceptable alternates ("accept USA or America")
 * @param promptable     answers that should trigger a prompt ("prompt on Madison")
 * @param rejected       explicitly disallowed answers ("do not accept steel")
 * @param clarifications parentheticals kept for display, ignored for matching
 * @param display        a human-readable rendering of the answer line
 */
public record ParsedAnswer(
        String primary,
        String core,
        Set<String> accepted,
        Set<String> promptable,
        Set<String> rejected,
        List<String> clarifications,
        String display
) {}
