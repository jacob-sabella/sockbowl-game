package com.soulsoftworks.sockbowlgame.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHandlingRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SessionOrderedExecutorTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void oneSessionsTasksRunOneAtATimeInSubmissionOrder() throws Exception {
        SessionOrderedExecutor executor = new SessionOrderedExecutor(pool, 16);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(200);
        for (int i = 0; i < 200; i++) {
            int n = i;
            executor.execute(task("session-a", () -> {
                order.add(n);
                done.countDown();
            }));
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            expected.add(i);
        }
        assertThat(order).containsExactlyElementsOf(expected);
    }

    @Test
    void differentSessionsRunInParallel() throws Exception {
        // Pick two ids on different lanes of a 16-lane executor.
        SessionOrderedExecutor executor = new SessionOrderedExecutor(pool, 16);
        String first = "session-a";
        String second = "session-b";
        while (Math.floorMod(second.hashCode(), 16) == Math.floorMod(first.hashCode(), 16)) {
            second = second + "x";
        }
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);
        Runnable meetThenFinish = () -> {
            bothRunning.countDown();
            try {
                if (bothRunning.await(5, TimeUnit.SECONDS)) {
                    done.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        executor.execute(task(first, meetThenFinish));
        executor.execute(task(second, meetThenFinish));
        assertThat(done.await(10, TimeUnit.SECONDS)).as("both sessions ran at the same time").isTrue();
    }

    @Test
    void tasksWithoutASessionGoStraightToThePool() throws Exception {
        SessionOrderedExecutor executor = new SessionOrderedExecutor(pool, 16);
        CountDownLatch ran = new CountDownLatch(1);
        executor.execute(ran::countDown);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static MessageHandlingRunnable task(String sessionId, Runnable body) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        return new MessageHandlingRunnable() {
            @Override
            public Message<?> getMessage() {
                return message;
            }

            @Override
            public MessageHandler getMessageHandler() {
                return m -> { };
            }

            @Override
            public void run() {
                body.run();
            }
        };
    }
}
