package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.model.TradePlan;
import com.gusev.replaytrainer.sim.TradeOutcome;
import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.sim.TradeSpec;

public record TradeReport(String direction, String entryType, Double limit, Double stop, Double target,
		String rationale, OutcomeDto outcome) {

	public static TradeReport of(TradeSpec spec, TradeOutcome outcome) {
		if (spec.direction() == TradeDirection.SKIP) {
			return new TradeReport("SKIP", null, null, null, null, null, null);
		}
		return new TradeReport(spec.direction().name(), spec.entryType().name(),
				spec.limit() > 0 ? spec.limit() : null, spec.stop(), spec.target(), null, OutcomeDto.from(outcome));
	}

	public static TradeReport of(TradePlan plan, TradeOutcome outcome) {
		if (plan.direction() == TradeDirection.SKIP) {
			return new TradeReport("SKIP", null, null, null, null, plan.rationale(), null);
		}
		return new TradeReport(plan.direction().name(), plan.entryType().name(),
				plan.limit() > 0 ? plan.limit() : null, plan.stop(), plan.target(), plan.rationale(),
				OutcomeDto.from(outcome));
	}
}
