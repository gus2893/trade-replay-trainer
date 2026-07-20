package com.gusev.replaytrainer.sim;

/**
 * A trade intention placed at the scenario cut point. The reference price is
 * the last visible close; the simulated fill happens at the next bar's open.
 */
public record TradeSpec(TradeDirection direction, double stop, double target) {

	public void validateAgainst(double referencePrice) {
		if (direction == TradeDirection.SKIP) {
			return;
		}
		if (stop <= 0 || target <= 0) {
			throw new IllegalArgumentException("Stop and target must be positive prices");
		}
		if (direction == TradeDirection.LONG) {
			if (stop >= referencePrice) {
				throw new IllegalArgumentException("Long stop must be below the current price " + referencePrice);
			}
			if (target <= referencePrice) {
				throw new IllegalArgumentException("Long target must be above the current price " + referencePrice);
			}
		} else {
			if (stop <= referencePrice) {
				throw new IllegalArgumentException("Short stop must be above the current price " + referencePrice);
			}
			if (target >= referencePrice) {
				throw new IllegalArgumentException("Short target must be below the current price " + referencePrice);
			}
		}
	}
}
