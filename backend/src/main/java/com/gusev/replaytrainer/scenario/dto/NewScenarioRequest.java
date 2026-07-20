package com.gusev.replaytrainer.scenario.dto;

/**
 * symbol: null/blank for a random mystery symbol, or a specific one (e.g. TSLA, BTC).
 * includeCrypto: whether crypto symbols may appear in the random pool (default true).
 */
public record NewScenarioRequest(String symbol, Boolean includeCrypto) {

	public boolean cryptoAllowed() {
		return includeCrypto == null || includeCrypto;
	}
}
