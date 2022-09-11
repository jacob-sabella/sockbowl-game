package com.soulsoftworks.sockbowlgame;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRedisDocumentRepositories
public class SockbowlGameApplication {

	public static void main(String[] args) {
		SpringApplication.run(SockbowlGameApplication.class, args);
	}

}
