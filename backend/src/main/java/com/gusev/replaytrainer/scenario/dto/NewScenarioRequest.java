package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.scenario.CutPhase;

/**
 * symbol: null/blank for a random mystery symbol, or a specific one (e.g. TSLA, BTC, EURUSD).
 * includeCrypto / includeForex: whether those asset classes may appear in the random pool.
 * phase: ANY (default) or OPEN — cut only within the first 90 minutes, for ORB reps.
 */
public record NewScenarioRequest(String symbol, Boolean includeCrypto, Boolean includeForex, String phase) {

	public boolean cryptoAllowed() {
		return includeCrypto == null || includeCrypto;
	}

	public boolean forexAllowed() {
		return includeForex == null || includeForex;
	}

	public CutPhase cutPhase() {
		try {
			return phase == null ? CutPhase.ANY : CutPhase.valueOf(phase.toUpperCase());
		} catch (IllegalArgumentException e) {
			return CutPhase.ANY;
		}
	}
}
