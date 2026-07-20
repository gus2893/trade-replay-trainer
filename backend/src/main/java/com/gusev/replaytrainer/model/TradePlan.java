package com.gusev.replaytrainer.model;

import java.util.Map;

import com.gusev.replaytrainer.sim.TradeDirection;

/**
 * The model's proposed trade for a scenario. Direction SKIP means the model
 * sees no edge and passes — a legitimate answer that also gets rated.
 * features is the exact indicator snapshot the policy decided on, persisted to
 * the training log so records are trainable without re-deriving context.
 */
public record TradePlan(TradeDirection direction, double stop, double target, String rationale,
		Map<String, Double> features) {

	public static TradePlan skip(String rationale, Map<String, Double> features) {
		return new TradePlan(TradeDirection.SKIP, 0, 0, rationale, features);
	}
}
