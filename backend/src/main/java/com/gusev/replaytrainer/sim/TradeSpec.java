package com.gusev.replaytrainer.sim;

/**
 * A trade intention placed at the scenario cut point. MARKET fills at the next
 * bar's open; LIMIT rests at a better price (long below the tape, short above)
 * and expires unfilled if never touched. Stop and target are validated against
 * the intended entry basis (the limit price for LIMIT orders).
 */
public record TradeSpec(TradeDirection direction, EntryType entryType, double limit, double stop, double target) {

	/** Market-order convenience, used everywhere pre-limit code paths exist. */
	public TradeSpec(TradeDirection direction, double stop, double target) {
		this(direction, EntryType.MARKET, 0, stop, target);
	}

	public void validateAgainst(double referencePrice) {
		if (direction == TradeDirection.SKIP) {
			return;
		}
		if (stop <= 0 || target <= 0) {
			throw new IllegalArgumentException("Stop and target must be positive prices");
		}
		double basis = referencePrice;
		if (entryType == EntryType.LIMIT) {
			if (limit <= 0) {
				throw new IllegalArgumentException("Limit orders need a limit price");
			}
			if (direction == TradeDirection.LONG && limit > referencePrice) {
				throw new IllegalArgumentException(
						"A long limit rests below the market (" + referencePrice + "), got " + limit);
			}
			if (direction == TradeDirection.SHORT && limit < referencePrice) {
				throw new IllegalArgumentException(
						"A short limit rests above the market (" + referencePrice + "), got " + limit);
			}
			basis = limit;
		}
		if (direction == TradeDirection.LONG) {
			if (stop >= basis) {
				throw new IllegalArgumentException("Long stop must be below the entry price " + basis);
			}
			if (target <= basis) {
				throw new IllegalArgumentException("Long target must be above the entry price " + basis);
			}
		} else {
			if (stop <= basis) {
				throw new IllegalArgumentException("Short stop must be above the entry price " + basis);
			}
			if (target >= basis) {
				throw new IllegalArgumentException("Short target must be below the entry price " + basis);
			}
		}
	}
}
