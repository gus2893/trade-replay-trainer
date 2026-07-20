package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.sim.TradeOutcome;

public record OutcomeDto(
		double entryFill,
		double exitPrice,
		long exitTime,
		int entryBarIndex,
		int exitBarIndex,
		String exitReason,
		double r,
		double mfeR,
		double maeR) {

	public static OutcomeDto from(TradeOutcome o) {
		if (o == null) {
			return null;
		}
		return new OutcomeDto(o.entryFill(), o.exitPrice(), o.exitTime().getEpochSecond(), o.entryBarIndex(),
				o.exitBarIndex(), o.exitReason().name(), o.rMultiple(), o.mfeR(), o.maeR());
	}
}
