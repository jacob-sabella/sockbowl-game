package com.soulsoftworks.sockbowlgame.ratelimit;

import com.google.gson.JsonObject;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-G2 acceptance (M4-RL-03): the REST request guard trips each shipped game
 * policy at its configured size and recovers when the clock advances by the
 * refill period, through the real auth-on security chain and a real Redis.
 */
class RequestGuardFilterIT extends RequestGuardAuthOnITSupport {

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private RateLimitRedis rateLimitRedis;

    private MvcResult create(RequestPostProcessor caller, String ip) throws Exception {
        return mvc.perform(post(CREATE).with(caller).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andReturn();
    }

    private static void assertRateLimited(MvcResult result, String policy, long limit) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo(policy);
        assertThat(result.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_LIMIT)).isEqualTo(Long.toString(limit));
        assertThat(result.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING)).isEqualTo("0");
        long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isPositive();

        JsonObject body = new com.google.gson.Gson().fromJson(result.getResponse().getContentAsString(), JsonObject.class);
        assertThat(body.get("error").getAsString()).isEqualTo("rate_limited");
        assertThat(body.get("policy").getAsString()).isEqualTo(policy);
        assertThat(body.get("retryAfterSeconds").getAsLong()).isEqualTo(retryAfter);
        assertThat(body.get("message").getAsString()).isEqualTo("Too many requests");
    }

    /* ------------------------------------------------------------------ */
    /* Wiring                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    void guardRunsInsideTheSecurityChainAfterBearerAuthAndBeforeAuthorization() {
        List<Filter> filters = filterChainProxy.getFilterChains().get(0).getFilters();
        int bearer = indexOf(filters, BearerTokenAuthenticationFilter.class);
        int guard = indexOf(filters, RequestGuardFilter.class);
        int authorization = indexOf(filters, AuthorizationFilter.class);
        assertThat(bearer).isNotNegative();
        assertThat(guard).isGreaterThan(bearer).isLessThan(authorization);
    }

    static int indexOf(List<Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /* ------------------------------------------------------------------ */
    /* session-create                                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void sessionCreateGuestTripsOnTheFourthAndRecoversAfterTenMinutes() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 3; i++) {
            MvcResult ok = create(ANON, ip);
            assertThat(ok.getResponse().getStatus()).as("guest create #%d", i).isEqualTo(200);
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("session-create");
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING))
                    .isEqualTo(Integer.toString(3 - i));
        }

        MvcResult limited = create(ANON, ip);
        assertRateLimited(limited, "session-create", 3);
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(1L, Duration.ofMinutes(10).toSeconds());

        // Another guest IP is unaffected.
        assertThat(create(ANON, nextIp()).getResponse().getStatus()).isEqualTo(200);

        CLOCK.advance(Duration.ofMinutes(10));
        assertThat(create(ANON, ip).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void sessionCreateRejectionIsRecordedToTheEventStream() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            create(ANON, ip);
        }
        assertRateLimited(create(ANON, ip), "session-create", 3);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<StreamMessage<String, String>> events =
                    rateLimitRedis.sync().xrange(UsageKeys.events(), Range.create("-", "+"));
            assertThat(events).anySatisfy(e -> {
                assertThat(e.getBody()).containsEntry("policy", "session-create")
                        .containsEntry("kind", "rate")
                        .containsEntry("svc", "game")
                        .containsEntry("ip", ip)
                        .containsEntry("path", CREATE);
            });
        });
    }

    @Test
    void sessionCreateAuthenticatedAuthorGetsTen() throws Exception {
        String sub = "kc-author-" + nextIp();
        for (int i = 1; i <= 10; i++) {
            // A different IP per call: the author is keyed by sub, not address.
            assertThat(create(author(sub), nextIp()).getResponse().getStatus()).as("author create #%d", i).isEqualTo(200);
        }
        assertRateLimited(create(author(sub), nextIp()), "session-create", 10);

        CLOCK.advance(Duration.ofMinutes(10));
        assertThat(create(author(sub), nextIp()).getResponse().getStatus()).isEqualTo(200);
    }

    /* ------------------------------------------------------------------ */
    /* session-join                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void joinByCodeTripsOnTheTwentyFirstPerIpAndRecoversAfterAMinute() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 20; i++) {
            // Unknown code: a 404 still costs a token (join-code brute force).
            int status = mvc.perform(post(JOIN_BY_CODE).with(from(ip))
                            .contentType(MediaType.APPLICATION_JSON).content(joinBody("NOPE" + i)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("join #%d", i).isEqualTo(404);
        }
        MvcResult limited = mvc.perform(post(JOIN_BY_CODE).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(joinBody("NOPE21")))
                .andReturn();
        assertRateLimited(limited, "session-join", 20);

        // session-join is keyed by IP even for a signed-in caller.
        assertRateLimited(mvc.perform(post(JOIN_BY_CODE).with(player("kc-joiner-" + ip)).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(joinBody("NOPE22")))
                .andReturn(), "session-join", 20);

        CLOCK.advance(Duration.ofMinutes(1));
        mvc.perform(post(JOIN_BY_CODE).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(joinBody("NOPE23")))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------------------------------------------ */
    /* default, used-questions, admin                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void defaultPolicyTripsOnTheHundredTwentyFirstGetAndRecovers() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 120; i++) {
            MvcResult ok = mvc.perform(get(AUTH_STATUS).with(from(ip))).andReturn();
            assertThat(ok.getResponse().getStatus()).as("GET #%d", i).isEqualTo(200);
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("default");
            assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING))
                    .isEqualTo(Integer.toString(120 - i));
        }
        MvcResult limited = mvc.perform(get(AUTH_STATUS).with(from(ip))).andReturn();
        assertRateLimited(limited, "default", 120);

        CLOCK.advance(Duration.ofMinutes(1));
        mvc.perform(get(AUTH_STATUS).with(from(ip))).andExpect(status().isOk());
    }

    @Test
    void usedQuestionsTripsOnTheThirtyFirstPerUserAndRecovers() throws Exception {
        String sub = "kc-used-" + nextIp();
        for (int i = 1; i <= 30; i++) {
            mvc.perform(post(USED_QUESTIONS).with(player(sub)).with(from(nextIp()))
                            .contentType(MediaType.APPLICATION_JSON).content("[\"q" + i + "\"]"))
                    .andExpect(status().isOk());
        }
        assertRateLimited(mvc.perform(post(USED_QUESTIONS).with(player(sub)).with(from(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"q31\"]")).andReturn(),
                "used-questions", 30);

        CLOCK.advance(Duration.ofMinutes(1));
        mvc.perform(post(USED_QUESTIONS).with(player(sub)).with(from(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"q32\"]"))
                .andExpect(status().isOk());
    }

    @Test
    void adminPolicyTripsForAModeratorAtThreeTimesCapacityAndRecovers() throws Exception {
        // MODERATOR multiplier 3.0: admin (120) and default (120) both scale to
        // 360; the route policy is charged first, so it is the one that trips.
        String sub = "kc-mod-" + nextIp();
        String ip = nextIp();
        for (int i = 1; i <= 360; i++) {
            MvcResult ok = mvc.perform(get(ADMIN_BANS).with(moderator(sub)).with(from(ip))).andReturn();
            assertThat(ok.getResponse().getStatus()).as("admin GET #%d", i).isEqualTo(200);
        }
        assertRateLimited(mvc.perform(get(ADMIN_BANS).with(moderator(sub)).with(from(ip))).andReturn(),
                "admin", 360);

        CLOCK.advance(Duration.ofMinutes(1));
        mvc.perform(get(ADMIN_BANS).with(moderator(sub)).with(from(ip))).andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ */
    /* Exemptions and CORS                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void actuatorHealthIsNeverLimited() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 200; i++) {
            MvcResult r = mvc.perform(get("/actuator/health").with(from(ip))).andReturn();
            assertThat(r.getResponse().getStatus()).as("health #%d", i).isIn(200, 503);
            assertThat(r.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isNull();
        }
        // ...and it did not drain the caller's default bucket either.
        MvcResult after = mvc.perform(get(AUTH_STATUS).with(from(ip))).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(200);
        assertThat(after.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING)).isEqualTo("119");
    }

    @Test
    void corsPreflightExposesTheLimitHeadersAndIsNotCharged() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 10; i++) {
            MvcResult preflight = mvc.perform(options(CREATE).with(from(ip))
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,authorization"))
                    .andReturn();
            assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
            assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
            assertExposesLimitHeaders(preflight);
        }
        // Ten preflights did not use any of the guest's three creates.
        for (int i = 0; i < 3; i++) {
            assertThat(create(ANON, ip).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void rateLimitedResponseCarriesCorsHeaders() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 3; i++) {
            create(ANON, ip);
        }
        MvcResult limited = mvc.perform(post(CREATE).with(from(ip)).header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andReturn();
        assertRateLimited(limited, "session-create", 3);
        assertThat(limited.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGIN);
        assertExposesLimitHeaders(limited);
    }

    private static void assertExposesLimitHeaders(MvcResult result) {
        String exposed = String.join(",", result.getResponse().getHeaders(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
        assertThat(exposed.toLowerCase()).contains(
                "retry-after", "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-policy");
    }
}
