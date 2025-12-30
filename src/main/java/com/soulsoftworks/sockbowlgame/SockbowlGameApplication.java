package com.soulsoftworks.sockbowlgame;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashSet;
import java.util.Set;

@SpringBootApplication
@EnableRedisDocumentRepositories
@EnableConfigurationProperties
@EnableFeignClients
@EnableScheduling
public class SockbowlGameApplication {

	public static void main(String[] args) {
		String authEnabled = System.getenv("SOCKBOWL_AUTH_ENABLED");
		boolean isAuthDisabled = authEnabled == null || authEnabled.equals("false");

		if (isAuthDisabled) {
			SpringApplication app = new SpringApplication(SockbowlGameApplication.class);
			app.setDefaultProperties(java.util.Map.of(
				"spring.autoconfigure.exclude",
				"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
				"org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
				"org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"
			));
			app.run(args);
		} else {
			SpringApplication.run(SockbowlGameApplication.class, args);
		}
	}

}
