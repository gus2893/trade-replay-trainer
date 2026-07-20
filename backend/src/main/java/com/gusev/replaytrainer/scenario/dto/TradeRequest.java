package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.sim.EntryType;
import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.sim.TradeSpec;

import jakarta.validation.constraints.NotNull;

public record TradeRequest(@NotNull TradeDirection direction, String entryType, Double limit, Double stop,
		Double target) {

	public TradeSpec toSpec() {
		if (direction == TradeDirection.SKIP) {
			return new TradeSpec(TradeDirection.SKIP, 0, 0);
		}
		if (stop == null || target == null) {
			throw new IllegalArgumentException("Stop and target are required unless skipping");
		}
		EntryType type = "LIMIT".equalsIgnoreCase(entryType) ? EntryType.LIMIT : EntryType.MARKET;
		if (type == EntryType.LIMIT && limit == null) {
			throw new IllegalArgumentException("Limit orders need a limit price");
		}
		return new TradeSpec(direction, type, type == EntryType.LIMIT ? limit : 0, stop, target);
	}
}
