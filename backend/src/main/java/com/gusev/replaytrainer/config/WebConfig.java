package com.gusev.replaytrainer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

	@Bean
	WebMvcConfigurer corsConfigurer(AppProperties props) {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/api/**")
						.allowedOrigins(props.allowedOrigins().toArray(String[]::new))
						.allowedMethods("GET", "POST", "OPTIONS");
			}
		};
	}
}
