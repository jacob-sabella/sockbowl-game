package org.springframework.boot.autoconfigure.web.servlet;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Spring Boot 4.0 compatibility bridge.
 * Empty placeholder - actual config is org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
 */
@Deprecated
@AutoConfiguration(after = org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
public class WebMvcAutoConfiguration {
	// Empty bridge class - all configuration is handled by the new WebMvcAutoConfiguration
}
