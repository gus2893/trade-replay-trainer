package com.gusev.replaytrainer.sim;

import java.time.Instant;

/**
 * Result of walking a trade forward through the hidden bars.
 * R-multiples are measured against the initial risk (entry to stop distance),
 * the same convention as the paper-trade labs' ledgers.
 */
public record TradeOutcome(
		double entryFill,
		double exitPrice,
		Instant exitTime,
		int exitBarIndex,
		ExitReason exitReason,
		double rMultiple,
		double mfeR,
		double maeR) {

	public enum ExitReason {
		STOP, TARGET, END_OF_DATA
	}
}
