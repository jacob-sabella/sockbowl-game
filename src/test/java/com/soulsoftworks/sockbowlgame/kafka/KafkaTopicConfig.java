package com.soulsoftworks.sockbowlgame.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaTopicConfig {

    @Value(value = "${sockbowl.kafka.topic.game-topic}")
    private String gameTopic;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Bean
    public NewTopic topic1() {
        return TopicBuilder.name(gameTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}

