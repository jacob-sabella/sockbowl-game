package com.soulsoftworks.sockbowlgame.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.context.EmbeddedKafka;

@Configuration
@EnableAutoConfiguration(excludeName = {"org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"})
public class KafkaTopicConfig {
}

