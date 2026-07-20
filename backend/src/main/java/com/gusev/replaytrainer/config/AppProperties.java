package com.gusev.replaytrainer.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paths point at the paper-trade labs' cached bar CSVs; the backend never
 * needs API keys — everything is read from local disk.
 */
@ConfigurationProperties(prefix = "trainer")
public record AppProperties(
		List<String> stockDataDirs,
		List<String> cryptoDataDirs,
		String trainingLog,
		List<String> allowedOrigins,
		Boolean selfPlay,
		String learnedModel) {

	public boolean selfPlayEnabled() {
		return selfPlay == null || selfPlay;
	}

	public String learnedModelPath() {
		return learnedModel != null ? learnedModel : "./data/learned_model.json";
	}
}
