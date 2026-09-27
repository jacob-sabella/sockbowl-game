package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${sockbowl.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @PostConstruct
    public void logConfig() {
        log.info("=================================================");
        log.info("Kafka Configuration:");
        log.info("Bootstrap Servers: {}", bootstrapServers);
        log.info("Consumer Group ID: game-consumers");
        log.info("=================================================");
    }

    @Bean
    public ProducerFactory<String, SockbowlInMessage> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }


    @Bean
    public KafkaTemplate<String, SockbowlInMessage> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public Map<String, Object> consumerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "game-consumers");
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
        // Spring's default is "latest", which means a *newly formed* consumer
        // group (a fresh broker, or the group's offsets having expired/been
        // wiped) starts reading only from messages produced *after* its first
        // partition assignment. Anything produced in the window between the
        // container starting and that first rebalance completing (which has
        // been observed to take tens of seconds on a fresh stack while the
        // broker and consumer group stabilize) is silently skipped: the SEND
        // succeeds, the message lands on the topic, and nobody ever consumes
        // it (M2-LIVE-01). "earliest" makes a fresh/reset group start from the
        // beginning of the topic instead, so no message produced before this
        // consumer's first assignment is lost. It has no effect on a group
        // with already-committed offsets (mid-life restarts still resume from
        // the last committed offset either way).
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    @Bean
    public ConsumerFactory<String, SockbowlInMessage> consumerFactory() {
        JacksonJsonDeserializer<SockbowlInMessage> deserializer = new JacksonJsonDeserializer<>(SockbowlInMessage.class);
        // Trust only our own message packages, not "*" — avoids deserializing
        // arbitrary attacker-supplied types from the game topic.
        deserializer.addTrustedPackages("com.soulsoftworks.sockbowlgame");
        return new DefaultKafkaConsumerFactory<>(consumerConfigs(), new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SockbowlInMessage> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SockbowlInMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(errorHandler());
        log.info("Kafka listener container factory created successfully");
        return factory;
    }

    @Bean
    public CommonErrorHandler errorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1000L, 2L));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        return handler;
    }
}
