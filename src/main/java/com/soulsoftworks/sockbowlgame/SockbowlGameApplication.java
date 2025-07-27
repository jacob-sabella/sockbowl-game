package com.soulsoftworks.sockbowlgame;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableRedisDocumentRepositories
@EnableConfigurationProperties
@EnableFeignClients
public class SockbowlGameApplication {

	public static void main(String[] args) {
		SpringApplication.run(SockbowlGameApplication.class, args);
	}

}
