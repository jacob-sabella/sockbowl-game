package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.TestcontainersUtil;
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

@Testcontainers
@SpringBootTest
@EnableKafka
public class KafkaServiceTest {

    @Container
    private static final KafkaContainer kafkaContainer = TestcontainersUtil.getKafkaContainer();

    @Autowired
    private KafkaService kafkaService;


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
    void sendMessageTest() throws InterruptedException {
        TestSockbowlInMessage testSockbowlMessage = new TestSockbowlInMessage(null, null);

        kafkaService.sendMessage(testSockbowlMessage);

        // wait for the message to be delivered
        ConsumerRecord<String, SockbowlInMessage> receivedMessage = records.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedMessage);
        assertEquals("GENERIC", receivedMessage.value().getMessageType());
    }

    @KafkaListener(topics = "${sockbowl.kafka.topic.game-topic}", groupId = "game-consumers" )
    public void listen(ConsumerRecord<String, SockbowlInMessage> record) {
        records.add(record);
    }

}
