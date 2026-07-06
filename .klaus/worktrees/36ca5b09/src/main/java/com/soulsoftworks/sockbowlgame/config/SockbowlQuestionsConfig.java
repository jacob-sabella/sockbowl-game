package com.soulsoftworks.sockbowlgame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sockbowl.questions")
@Data
public class SockbowlQuestionsConfig {
    private String url;
}
