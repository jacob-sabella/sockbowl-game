package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.StompError;
import com.soulsoftworks.sockbowlgame.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;
import com.soulsoftworks.sockbowlgame.security.stomp.StompRejectedException;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 extends M2's non-fatal STOMP error advice (plan m4-limits section 2.5):
 * limiter fields on rejections, {@code QUOTA_EXCEEDED}, and
 * {@link IllegalArgumentException} as a {@link ProcessError} instead of being
 * swallowed.
 */
class StompExceptionAdviceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T22:00:00Z"));
    private final StompExceptionAdvice advice = advice(clock);

    private static StompExceptionAdvice advice(Clock clock) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", clock);
        return new StompExceptionAdvice(beans.getBeanProvider(Clock.class));
    }

    @Test
    void rejectionCarriesPolicyAndRetryAfterMs() {
        StompError error = advice.handleRejected(
                new StompRejectedException(StompErrorCode.RATE_LIMITED, "slow down", 3, "stomp-buzz"));
        assertThat(error.getCode()).isEqualTo("RATE_LIMITED");
        assertThat(error.getRetryAfterSeconds()).isEqualTo(3);
        assertThat(error.getRetryAfterMs()).isEqualTo(3000);
        assertThat(error.getPolicy()).isEqualTo("stomp-buzz");
    }

    @Test
    void m2RejectionsAreUnchanged() {
        StompError error = advice.handleRejected(
                new StompRejectedException(StompErrorCode.FORBIDDEN_DESTINATION, "no"));
        assertThat(error.getCode()).isEqualTo("FORBIDDEN_DESTINATION");
        assertThat(error.getRetryAfterSeconds()).isNull();
        assertThat(error.getRetryAfterMs()).isNull();
        assertThat(error.getPolicy()).isNull();
    }

    @Test
    void concurrentQuotaIsQuotaExceededWithoutRetry() {
        StompError error = advice.handleQuotaExceeded(new QuotaExceededException("hosted-sessions", 2, 2, null));
        assertThat(error.getMessageType()).isEqualTo("StompError");
        assertThat(error.getCode()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(error.getPolicy()).isEqualTo("hosted-sessions");
        assertThat(error.getMessage()).contains("hosted-sessions");
        assertThat(error.getRetryAfterSeconds()).isNull();
    }

    @Test
    void dailyQuotaRetriesAtTheReset() {
        StompError error = advice.handleQuotaExceeded(new QuotaExceededException("ai.generations", 20, 20,
                Instant.parse("2026-09-28T00:00:00Z")));
        assertThat(error.getRetryAfterSeconds()).isEqualTo(7200);
        assertThat(error.getRetryAfterMs()).isEqualTo(7_200_000L);
    }

    @Test
    void illegalArgumentBecomesAGenericProcessError() {
        ProcessError error = advice.handleIllegalArgument(
                new IllegalArgumentException("No enum constant com.example.Secret.X"));
        assertThat(error.getCode()).isEqualTo("INVALID_REQUEST");
        assertThat(error.getError()).isEqualTo("Invalid request").doesNotContain("com.example");
    }

    @Test
    void newHandlersReplyOnTheUserErrorsQueue() throws Exception {
        for (String name : new String[]{"handleQuotaExceeded", "handleIllegalArgument"}) {
            var method = java.util.Arrays.stream(StompExceptionAdvice.class.getMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            assertThat(method.getAnnotation(MessageExceptionHandler.class)).isNotNull();
            SendToUser sendToUser = method.getAnnotation(SendToUser.class);
            assertThat(sendToUser.destinations()).containsExactly("/queue/errors");
            assertThat(sendToUser.broadcast()).isFalse();
        }
    }
}
