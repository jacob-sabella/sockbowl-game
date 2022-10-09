package com.soulsoftworks.sockbowlgame;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRedisDocumentRepositories
@EnableConfigurationProperties
public class SockbowlGameApplication {

	public static void main(String[] args) {
		SpringApplication.run(SockbowlGameApplication.class, args);
	}

}
