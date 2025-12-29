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
		SpringApplication app = new SpringApplication(SockbowlGameApplication.class);

		// Conditionally exclude autoconfiguration based on auth.enabled property
		String authEnabled = System.getenv("SOCKBOWL_AUTH_ENABLED");
		if (authEnabled == null || authEnabled.equals("false")) {
			app.setAdditionalProfiles("no-auth");
		}

		app.run(args);
	}

}
