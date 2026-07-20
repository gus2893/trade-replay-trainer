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
		String learnedModel,
		Boolean bootstrap,
		Integer backfillDays,
		List<String> symbols,
		String syncRepo) {

	public boolean selfPlayEnabled() {
		return selfPlay == null || selfPlay;
	}

	public String learnedModelPath() {
		return learnedModel != null ? learnedModel : "./data/learned_model.json";
	}

	/** Cloud mode: fetch bar data at startup instead of reading the labs' CSVs. */
	public boolean bootstrapEnabled() {
		return bootstrap != null && bootstrap;
	}

	public int backfillDaysOrDefault() {
		return backfillDays != null ? backfillDays : 150;
	}

	public List<String> symbolsOrEmpty() {
		return symbols == null ? List.of() : symbols;
	}

	/** Private GitHub repo ("owner/name") that persists the training state across redeploys. */
	public String syncRepoOrNull() {
		return syncRepo == null || syncRepo.isBlank() ? null : syncRepo;
	}
}
