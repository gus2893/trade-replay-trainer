package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.model.TradePlan;
import com.gusev.replaytrainer.sim.TradeOutcome;
import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.sim.TradeSpec;

public record TradeReport(String direction, Double stop, Double target, String rationale, OutcomeDto outcome) {

	public static TradeReport of(TradeSpec spec, TradeOutcome outcome) {
		if (spec.direction() == TradeDirection.SKIP) {
			return new TradeReport("SKIP", null, null, null, null);
		}
		return new TradeReport(spec.direction().name(), spec.stop(), spec.target(), null, OutcomeDto.from(outcome));
	}

	public static TradeReport of(TradePlan plan, TradeOutcome outcome) {
		if (plan.direction() == TradeDirection.SKIP) {
			return new TradeReport("SKIP", null, null, plan.rationale(), null);
		}
		return new TradeReport(plan.direction().name(), plan.stop(), plan.target(), plan.rationale(),
				OutcomeDto.from(outcome));
	}
}
