package com.soulsoftworks.sockbowlgame.client;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls sockbowl-questions' {@code GET /api/admin/usage/content-counts} (plan
 * m4-limits section 2.8, WP-G6/Q4) for the admin usage page's
 * {@code packetsOwned}/{@code questionsCreated} columns.
 *
 * <p>Relays the <b>calling admin's own bearer token</b> (A7: least privilege -
 * the game/questions service account never gets admin powers on questions).
 * Questions caps a single call at 100 subjects and answers 400 past that, so
 * {@link #contentCounts} chunks the page itself.
 *
 * <p>Q4 (which serves this endpoint) had not yet merged when this class was
 * written; it is coded strictly against the plan's contract
 * ({@code {sub: {packetsOwned, questionsCreated}}}) and exercised against a
 * stand-in HTTP server in tests. Any failure (unreachable, timeout, non-2xx,
 * unparsable body) is swallowed and reported as "unavailable" (D12-style fail
 * open for a display-only, non-security-critical call): the admin page must
 * still render with every other column intact.
 */
@Slf4j
@Component
public class QuestionsUsageClient {

    /** Matches Q4's per-call cap; a page is chunked into groups of at most this size. */
    static final int MAX_SUBS_PER_CALL = 100;

    private static final Gson GSON = new Gson();
    private static final Type COUNTS_MAP_TYPE = TypeToken.getParameterized(Map.class, String.class, ContentCounts.class)
            .getType();

    private final RestClient restClient;

    public QuestionsUsageClient(SockbowlQuestionsConfig config) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(config.getTimeout());
        requestFactory.setReadTimeout(config.getTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(config.getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Content counts for every given subject, or {@code null} when
     * sockbowl-questions could not be reached (the caller shows "unavailable"
     * rather than failing the whole admin page). Never throws.
     *
     * @param subs        Keycloak subjects to look up; a no-op (empty map) for an empty list
     * @param bearerToken the admin's own JWT, forwarded as-is
     */
    public Map<String, ContentCounts> contentCounts(List<String> subs, String bearerToken) {
        if (subs == null || subs.isEmpty()) {
            return Map.of();
        }
        Map<String, ContentCounts> merged = new LinkedHashMap<>();
        for (int start = 0; start < subs.size(); start += MAX_SUBS_PER_CALL) {
            List<String> chunk = subs.subList(start, Math.min(subs.size(), start + MAX_SUBS_PER_CALL));
            Map<String, ContentCounts> partial = fetchOne(chunk, bearerToken);
            if (partial == null) {
                return null;
            }
            merged.putAll(partial);
        }
        return merged;
    }

    private Map<String, ContentCounts> fetchOne(List<String> subs, String bearerToken) {
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("api/admin/usage/content-counts")
                            .queryParam("subs", String.join(",", subs))
                            .build())
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            Map<String, ContentCounts> parsed = GSON.fromJson(body, COUNTS_MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (RestClientException | JsonSyntaxException e) {
            log.warn("sockbowl-questions content-counts unavailable: {}", e.toString());
            return null;
        }
    }

    /** One subject's content counts, as sockbowl-questions (WP-Q4) reports them. */
    public record ContentCounts(long packetsOwned, long questionsCreated) {
    }
}
