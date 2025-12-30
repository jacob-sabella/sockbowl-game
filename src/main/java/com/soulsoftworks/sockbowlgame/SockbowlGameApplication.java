package com.soulsoftworks.sockbowlgame;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
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

		SpringApplication app = new SpringApplication(SockbowlGameApplication.class);

		// Programmatically exclude autoconfiguration when auth is disabled
		if (isAuthDisabled) {
			Set<String> exclusions = new HashSet<>();
			exclusions.add(DataSourceAutoConfiguration.class.getName());
			exclusions.add(HibernateJpaAutoConfiguration.class.getName());
			exclusions.add(OAuth2ClientAutoConfiguration.class.getName());
			app.setDefaultProperties(java.util.Map.of(
				"spring.autoconfigure.exclude", String.join(",", exclusions)
			));
		}

		app.run(args);
	}

}
