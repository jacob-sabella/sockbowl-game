package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.TestcontainersUtil;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.TestSockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static reactor.core.publisher.Mono.when;

@Testcontainers
@SpringBootTest
@EnableKafka
public class GameMessageServiceTest {

    @Container
    private static final KafkaContainer kafkaContainer = TestcontainersUtil.getKafkaContainer();

    @Autowired
    private GameMessageService gameMessageService;


    private final BlockingQueue<ConsumerRecord<String, SockbowlInMessage>> records = new LinkedBlockingDeque<>();

    private CountDownLatch latch = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws InterruptedException {
        // Allow some time for the listener to start
        latch.await(5, TimeUnit.SECONDS);
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
        System.out.println(kafkaContainer.getBootstrapServers());
    }

    @Test
    void shouldDeliverGenericMessageSuccessfully() throws InterruptedException {

        TestSockbowlInMessage testSockbowlMessage = new TestSockbowlInMessage();

        gameMessageService.sendMessage("game-topic-test", testSockbowlMessage);

        // wait for the message to be delivered
        ConsumerRecord<String, SockbowlInMessage> receivedMessage = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedMessage);
        assertEquals(MessageTypes.GENERIC, receivedMessage.value().getMessageType());
    }

    @KafkaListener(topics = "game-topic-test", groupId = "game-consumers" )
    public void fakeListen(ConsumerRecord<String, SockbowlInMessage> record) {
        records.add(record);
    }

}
