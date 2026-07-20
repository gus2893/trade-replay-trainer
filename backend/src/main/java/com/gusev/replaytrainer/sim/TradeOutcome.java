package com.gusev.replaytrainer.sim;

import java.time.Instant;

/**
 * Result of walking a trade forward through the hidden bars.
 * R-multiples are measured against the initial risk (entry to stop distance),
 * the same convention as the paper-trade labs' ledgers. entryBarIndex is 0 for
 * market orders and the touch bar for limits; NOT_FILLED means the limit was
 * never reached and no risk was ever taken (r = 0).
 */
public record TradeOutcome(
		double entryFill,
		double exitPrice,
		Instant exitTime,
		int entryBarIndex,
		int exitBarIndex,
		ExitReason exitReason,
		double rMultiple,
		double mfeR,
		double maeR) {

	public enum ExitReason {
		STOP, TARGET, END_OF_DATA, NOT_FILLED
	}
}
