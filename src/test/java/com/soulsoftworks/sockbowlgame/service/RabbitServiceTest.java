package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
@SpringBootTest
public class RabbitServiceTest {

    @Container
    private static final RabbitMQContainer rabbitMQContainer = TestcontainersUtil.getRabbitMQContainer();

    @Autowired
    private RabbitService rabbitService;

    @Autowired
    private RabbitTemplate rabbitTemplate;


    @DynamicPropertySource
    static void rabbitMqProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitMQContainer::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQContainer::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Test
    void enqueueMessageTest() {
        TestSockbowlInMessage testSockbowlMessage = new TestSockbowlInMessage();

        rabbitService.enqueueMessage(testSockbowlMessage);

        // wait for the message to be delivered
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            SockbowlInMessage receivedMessage = (SockbowlInMessage) rabbitTemplate.receiveAndConvert("GAME_QUEUE");;
            assertNotNull(receivedMessage);
            assertEquals("GENERIC", receivedMessage.getMessageType());
        });
    }

    private static class TestSockbowlInMessage extends SockbowlInMessage {
        String testString = "TEST";

        @Override
        public String getMessageType() {
            return "GENERIC";
        }
    }

    @Bean
    public Queue gameQueue() {
        return new Queue("GAME_QUEUE", false);
    }
}
