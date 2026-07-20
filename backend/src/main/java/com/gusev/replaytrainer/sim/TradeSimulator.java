package com.gusev.replaytrainer.sim;

import java.util.List;

import com.gusev.replaytrainer.market.Bar;

/**
 * Lookahead-safe, bar-by-bar trade playback. Market orders fill at the first
 * hidden bar's open; limit orders fill on the first bar that touches the limit
 * (a gap through it fills at the open — price improvement), or expire
 * NOT_FILLED. When stop and target are both touched inside one bar the stop is
 * assumed to fill first — the conservative reading, since intra-bar ordering
 * is unknown.
 */
public final class TradeSimulator {

	private TradeSimulator() {
	}

	public static TradeOutcome simulate(TradeSpec spec, List<Bar> futureBars) {
		if (spec.direction() == TradeDirection.SKIP) {
			throw new IllegalArgumentException("Cannot simulate a skipped trade");
		}
		if (futureBars.isEmpty()) {
			throw new IllegalArgumentException("No future bars to simulate against");
		}
		boolean isLong = spec.direction() == TradeDirection.LONG;

		int entryIdx = 0;
		double entry;
		if (spec.entryType() == EntryType.LIMIT) {
			entryIdx = -1;
			for (int i = 0; i < futureBars.size(); i++) {
				Bar b = futureBars.get(i);
				if (isLong ? b.low() <= spec.limit() : b.high() >= spec.limit()) {
					entryIdx = i;
					break;
				}
			}
			if (entryIdx < 0) {
				Bar last = futureBars.get(futureBars.size() - 1);
				return new TradeOutcome(round(spec.limit()), round(spec.limit()), last.time(),
						futureBars.size() - 1, futureBars.size() - 1, TradeOutcome.ExitReason.NOT_FILLED, 0, 0, 0);
			}
			Bar fillBar = futureBars.get(entryIdx);
			entry = isLong ? Math.min(spec.limit(), fillBar.open()) : Math.max(spec.limit(), fillBar.open());
		} else {
			entry = futureBars.get(0).open();
		}

		double risk = Math.abs(entry - spec.stop());
		if (risk <= 0) {
			throw new IllegalArgumentException("Entry fill " + entry + " equals stop; risk would be zero");
		}

		double bestExcursion = 0;
		double worstExcursion = 0;
		for (int i = entryIdx; i < futureBars.size(); i++) {
			Bar bar = futureBars.get(i);
			double favorable = isLong ? bar.high() - entry : entry - bar.low();
			double adverse = isLong ? entry - bar.low() : bar.high() - entry;
			bestExcursion = Math.max(bestExcursion, favorable);
			worstExcursion = Math.max(worstExcursion, adverse);

			boolean stopHit = isLong ? bar.low() <= spec.stop() : bar.high() >= spec.stop();
			boolean targetHit = isLong ? bar.high() >= spec.target() : bar.low() <= spec.target();
			if (stopHit) {
				// Gap through the stop fills at the open, not at the stop price —
				// except on the entry bar of a limit fill, where the position
				// opened after the open; assume the stop price there.
				double gapRef = i == entryIdx ? spec.stop() : bar.open();
				double fill = isLong ? Math.min(spec.stop(), gapRef) : Math.max(spec.stop(), gapRef);
				return outcome(entry, fill, bar, entryIdx, i, TradeOutcome.ExitReason.STOP, risk, isLong,
						bestExcursion, worstExcursion);
			}
			if (targetHit) {
				double gapRef = i == entryIdx ? spec.target() : bar.open();
				double fill = isLong ? Math.max(spec.target(), gapRef) : Math.min(spec.target(), gapRef);
				return outcome(entry, fill, bar, entryIdx, i, TradeOutcome.ExitReason.TARGET, risk, isLong,
						bestExcursion, worstExcursion);
			}
		}
		Bar last = futureBars.get(futureBars.size() - 1);
		return outcome(entry, last.close(), last, entryIdx, futureBars.size() - 1,
				TradeOutcome.ExitReason.END_OF_DATA, risk, isLong, bestExcursion, worstExcursion);
	}

	private static TradeOutcome outcome(double entry, double exit, Bar exitBar, int entryIdx, int barIndex,
			TradeOutcome.ExitReason reason, double risk, boolean isLong,
			double bestExcursion, double worstExcursion) {
		double signedMove = isLong ? exit - entry : entry - exit;
		return new TradeOutcome(
				round(entry), round(exit), exitBar.time(), entryIdx, barIndex, reason,
				roundR(signedMove / risk), roundR(bestExcursion / risk), roundR(-worstExcursion / risk));
	}

	private static double round(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	private static double roundR(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
