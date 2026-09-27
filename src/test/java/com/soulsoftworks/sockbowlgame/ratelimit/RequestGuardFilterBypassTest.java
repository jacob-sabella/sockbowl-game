package com.soulsoftworks.sockbowlgame.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * WP-G2 bypass attempts against the request guard (auth on, real Redis):
 * <ul>
 *   <li>Client-supplied forwarding headers are not trusted under the default
 *       {@code server.forward-headers-strategy=none}: rotating
 *       {@code X-Forwarded-For} / {@code X-Real-IP} / {@code Forwarded} does not
 *       mint new per-IP buckets.</li>
 *   <li>A request with an <b>invalid</b> bearer is answered 401 by the resource
 *       server before it reaches the guard, so it is never rate limited, and it
 *       does not spend the caller's tokens either. This is the documented
 *       behaviour (the failed JWT decode is the only cost).</li>
 * </ul>
 */
class RequestGuardFilterBypassTest extends RequestGuardAuthOnITSupport {

    @Test
    void rotatingForwardedHeadersDoesNotResetThePerIpLimit() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 3; i++) {
            MvcResult ok = mvc.perform(post(CREATE).with(from(ip))
                            .header("X-Forwarded-For", "203.0.113." + i)
                            .header("X-Real-IP", "198.51.100." + i)
                            .header("Forwarded", "for=192.0.2." + i)
                            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                    .andReturn();
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult limited = mvc.perform(post(CREATE).with(from(ip))
                        .header("X-Forwarded-For", "203.0.113.99, 10.0.0.1")
                        .header("X-Real-IP", "198.51.100.99")
                        .header("Forwarded", "for=192.0.2.99")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("session-create");
    }

    @Test
    void rotatingForwardedHeadersDoesNotResetTheJoinLimit() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 20; i++) {
            mvc.perform(post(JOIN_BY_CODE).with(from(ip))
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .contentType(MediaType.APPLICATION_JSON).content(joinBody("NOPE" + i)));
        }
        MvcResult limited = mvc.perform(post(JOIN_BY_CODE).with(from(ip))
                        .header("X-Forwarded-For", "203.0.113.200")
                        .contentType(MediaType.APPLICATION_JSON).content(joinBody("NOPE21")))
                .andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("session-join");
    }

    @Test
    void invalidBearerIsAlways401AndNeverRateLimited() throws Exception {
        String ip = nextIp();
        // Well past the default policy's 120/min for this IP.
        for (int i = 1; i <= 130; i++) {
            MvcResult r = mvc.perform(get(AUTH_STATUS).with(from(ip)).header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as("invalid bearer #%d", i).isEqualTo(401);
            assertThat(r.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isNull();
        }
        // The rejected requests never reached the guard, so the IP's default bucket is untouched.
        MvcResult ok = mvc.perform(get(AUTH_STATUS).with(from(ip))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_REMAINING)).isEqualTo("119");
    }
}
