package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.sim.TradeSpec;

import jakarta.validation.constraints.NotNull;

public record TradeRequest(@NotNull TradeDirection direction, Double stop, Double target) {

	public TradeSpec toSpec() {
		if (direction == TradeDirection.SKIP) {
			return new TradeSpec(TradeDirection.SKIP, 0, 0);
		}
		if (stop == null || target == null) {
			throw new IllegalArgumentException("Stop and target are required unless skipping");
		}
		return new TradeSpec(direction, stop, target);
	}
}
