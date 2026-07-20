package com.gusev.replaytrainer.sim;

import java.util.List;

import com.gusev.replaytrainer.market.Bar;

/**
 * Lookahead-safe, bar-by-bar trade playback. Fill is the first hidden bar's
 * open (market order at the cut). When stop and target are both touched inside
 * one bar the stop is assumed to fill first — the conservative reading, since
 * intra-bar ordering is unknown.
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
		double entry = futureBars.get(0).open();
		double risk = Math.abs(entry - spec.stop());
		if (risk <= 0) {
			throw new IllegalArgumentException("Entry fill " + entry + " equals stop; risk would be zero");
		}

		double bestExcursion = 0;
		double worstExcursion = 0;
		for (int i = 0; i < futureBars.size(); i++) {
			Bar bar = futureBars.get(i);
			double favorable = isLong ? bar.high() - entry : entry - bar.low();
			double adverse = isLong ? entry - bar.low() : bar.high() - entry;
			bestExcursion = Math.max(bestExcursion, favorable);
			worstExcursion = Math.max(worstExcursion, adverse);

			boolean stopHit = isLong ? bar.low() <= spec.stop() : bar.high() >= spec.stop();
			boolean targetHit = isLong ? bar.high() >= spec.target() : bar.low() <= spec.target();
			if (stopHit) {
				// Gap through the stop fills at the open, not at the stop price.
				double fill = isLong ? Math.min(spec.stop(), bar.open()) : Math.max(spec.stop(), bar.open());
				return outcome(entry, fill, bar, i, TradeOutcome.ExitReason.STOP, risk, isLong,
						bestExcursion, worstExcursion);
			}
			if (targetHit) {
				double fill = isLong ? Math.max(spec.target(), bar.open()) : Math.min(spec.target(), bar.open());
				return outcome(entry, fill, bar, i, TradeOutcome.ExitReason.TARGET, risk, isLong,
						bestExcursion, worstExcursion);
			}
		}
		Bar last = futureBars.get(futureBars.size() - 1);
		return outcome(entry, last.close(), last, futureBars.size() - 1, TradeOutcome.ExitReason.END_OF_DATA,
				risk, isLong, bestExcursion, worstExcursion);
	}

	private static TradeOutcome outcome(double entry, double exit, Bar exitBar, int barIndex,
			TradeOutcome.ExitReason reason, double risk, boolean isLong,
			double bestExcursion, double worstExcursion) {
		double signedMove = isLong ? exit - entry : entry - exit;
		return new TradeOutcome(
				round(entry), round(exit), exitBar.time(), barIndex, reason,
				roundR(signedMove / risk), roundR(bestExcursion / risk), roundR(-worstExcursion / risk));
	}

	private static double round(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	private static double roundR(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
