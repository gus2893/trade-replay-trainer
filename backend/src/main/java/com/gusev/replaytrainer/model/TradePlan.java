package com.gusev.replaytrainer.model;

import com.gusev.replaytrainer.sim.TradeDirection;

/**
 * The model's proposed trade for a scenario. Direction SKIP means the model
 * sees no edge and passes — a legitimate answer that also gets rated.
 */
public record TradePlan(TradeDirection direction, double stop, double target, String rationale) {

	public static TradePlan skip(String rationale) {
		return new TradePlan(TradeDirection.SKIP, 0, 0, rationale);
	}
}
