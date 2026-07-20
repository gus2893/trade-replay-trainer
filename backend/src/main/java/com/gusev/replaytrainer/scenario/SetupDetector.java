package com.gusev.replaytrainer.scenario;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.sim.TradeDirection;

/**
 * Classifies a candidate cut by reading BOTH sides of it (server-side only —
 * the user never sees this until the reveal):
 *
 * Opportunity — did a clean tradeable move follow? Measured inside an eval
 * window right after the cut (~2 sessions of 5m bars / ~24h of 15m bars):
 * viable if the run reached >= 3.5 ATR with at most 1.5 ATR of drawdown on
 * the way to its peak. Both sides quiet (<= 1.75 ATR) = CHOP; both sides
 * violent = MIXED trap tape.
 *
 * Pattern — what a price-action trader could have seen AT the cut: a
 * liquidity sweep of the prior day's or a swing low/high that reclaimed, a
 * range breakout (with or against the move that followed), a >= 2 sigma VWAP
 * stretch, or a trend pullback. Priority order mirrors how specific each
 * read is; a big move with no textbook pattern is a MOMENTUM_RUN.
 */
public final class SetupDetector {

	private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");
	private static final double BIG_MOVE_ATR = 3.5;
	private static final double MAX_PULLBACK_ATR = 1.5;
	private static final double CHOP_ATR = 1.75;
	private static final int RANGE_LOOKBACK = 20;
	private static final int SWING_LOOKBACK = 30;
	private static final int SWEEP_WINDOW = 6;

	private SetupDetector() {
	}

	public static SetupInfo classify(List<Bar> context, List<Bar> future, int barMinutes) {
		double atr = atr(context, 14);
		if (atr <= 0 || context.size() < 40 || future.isEmpty()) {
			return new SetupInfo(SetupInfo.SetupType.CHOP, TradeDirection.SKIP, 0, "Flat tape — nothing to trade");
		}
		double cutClose = context.get(context.size() - 1).close();
		int eval = Math.min(future.size(), barMinutes == 5 ? 156 : 96);

		// Path analysis: best excursion each way and the drawdown suffered before it.
		double maxUp = 0, maxDown = 0, runDown = 0, runUp = 0, maeToUpPeak = 0, maeToDownPeak = 0;
		for (int i = 0; i < eval; i++) {
			Bar b = future.get(i);
			runDown = Math.max(runDown, cutClose - b.low());
			runUp = Math.max(runUp, b.high() - cutClose);
			if (b.high() - cutClose > maxUp) {
				maxUp = b.high() - cutClose;
				maeToUpPeak = runDown;
			}
			if (cutClose - b.low() > maxDown) {
				maxDown = cutClose - b.low();
				maeToDownPeak = runUp;
			}
		}
		boolean longViable = maxUp >= BIG_MOVE_ATR * atr && maeToUpPeak <= MAX_PULLBACK_ATR * atr;
		boolean shortViable = maxDown >= BIG_MOVE_ATR * atr && maeToDownPeak <= MAX_PULLBACK_ATR * atr;

		if (!longViable && !shortViable) {
			if (maxUp <= CHOP_ATR * atr && maxDown <= CHOP_ATR * atr) {
				return new SetupInfo(SetupInfo.SetupType.CHOP, TradeDirection.SKIP, round(Math.max(maxUp, maxDown) / atr),
						String.format(Locale.ROOT,
								"Chop — price never left a ±%.1f ATR box after the cut. The right play was to pass.",
								CHOP_ATR));
			}
			return new SetupInfo(SetupInfo.SetupType.MIXED, TradeDirection.SKIP, round(Math.max(maxUp, maxDown) / atr),
					String.format(Locale.ROOT,
							"Two-sided trap tape: %.1f ATR up and %.1f ATR down with no clean run either way.",
							maxUp / atr, maxDown / atr));
		}

		TradeDirection dir = longViable && shortViable
				? (maxUp >= maxDown ? TradeDirection.LONG : TradeDirection.SHORT)
				: (longViable ? TradeDirection.LONG : TradeDirection.SHORT);
		double moveAtr = round((dir == TradeDirection.LONG ? maxUp : maxDown) / atr);

		return classifyPattern(context, barMinutes, atr, cutClose, dir, moveAtr);
	}

	private static SetupInfo classifyPattern(List<Bar> context, int barMinutes, double atr, double cutClose,
			TradeDirection dir, double moveAtr) {
		int n = context.size();
		boolean isLong = dir == TradeDirection.LONG;

		// Key liquidity levels: prior day's extreme and the recent swing (excluding the last 3 bars).
		double[] prevDay = previousDayExtremes(context, barMinutes);
		double swingLow = Double.MAX_VALUE, swingHigh = -Double.MAX_VALUE;
		for (int i = Math.max(0, n - 3 - SWING_LOOKBACK); i < n - 3; i++) {
			swingLow = Math.min(swingLow, context.get(i).low());
			swingHigh = Math.max(swingHigh, context.get(i).high());
		}
		double recentLow = Double.MAX_VALUE, recentHigh = -Double.MAX_VALUE;
		for (int i = Math.max(0, n - SWEEP_WINDOW); i < n; i++) {
			recentLow = Math.min(recentLow, context.get(i).low());
			recentHigh = Math.max(recentHigh, context.get(i).high());
		}

		// Sweep = the tape poked through a level within the last few bars but the cut close reclaimed it.
		String sweptLevel = null;
		if (isLong) {
			if (prevDay != null && recentLow < prevDay[0] && cutClose > prevDay[0]) {
				sweptLevel = "the prior day's low";
			} else if (swingLow < Double.MAX_VALUE && recentLow < swingLow && cutClose > swingLow) {
				sweptLevel = "the swing low";
			}
		} else {
			if (prevDay != null && recentHigh > prevDay[1] && cutClose < prevDay[1]) {
				sweptLevel = "the prior day's high";
			} else if (swingHigh > -Double.MAX_VALUE && recentHigh > swingHigh && cutClose < swingHigh) {
				sweptLevel = "the swing high";
			}
		}
		if (sweptLevel != null) {
			return new SetupInfo(SetupInfo.SetupType.LIQUIDITY_SWEEP, dir, moveAtr, String.format(Locale.ROOT,
					"Liquidity sweep: the tape ran %s and reclaimed it — stops were taken, then price ran %.1f ATR %s.",
					sweptLevel, moveAtr, isLong ? "up" : "down"));
		}

		// Range breakout at the cut (range excludes the last bar so its close can break it).
		double rangeHigh = -Double.MAX_VALUE, rangeLow = Double.MAX_VALUE;
		for (int i = Math.max(0, n - 1 - RANGE_LOOKBACK); i < n - 1; i++) {
			rangeHigh = Math.max(rangeHigh, context.get(i).high());
			rangeLow = Math.min(rangeLow, context.get(i).low());
		}
		boolean brokeUp = cutClose > rangeHigh;
		boolean brokeDown = cutClose < rangeLow;
		if ((isLong && brokeUp) || (!isLong && brokeDown)) {
			return new SetupInfo(SetupInfo.SetupType.BREAKOUT_CONTINUATION, dir, moveAtr, String.format(Locale.ROOT,
					"Breakout continuation: the cut closed %s the %d-bar range and follow-through ran %.1f ATR.",
					isLong ? "above" : "below", RANGE_LOOKBACK, moveAtr));
		}
		if ((isLong && brokeDown) || (!isLong && brokeUp)) {
			return new SetupInfo(SetupInfo.SetupType.FAILED_BREAKOUT, dir, moveAtr, String.format(Locale.ROOT,
					"Failed breakout: price broke %s the range but the move failed and reversed %.1f ATR %s — a fade setup.",
					isLong ? "below" : "above", moveAtr, isLong ? "up" : "down"));
		}

		// VWAP stretch >= 2 sigma against the coming move = mean reversion.
		double[] vwapSigma = sessionVwapSigma(context, barMinutes);
		if (vwapSigma != null) {
			boolean stretchedDown = cutClose < vwapSigma[0] - 2 * vwapSigma[1];
			boolean stretchedUp = cutClose > vwapSigma[0] + 2 * vwapSigma[1];
			if ((isLong && stretchedDown) || (!isLong && stretchedUp)) {
				return new SetupInfo(SetupInfo.SetupType.VWAP_REVERSION, dir, moveAtr, String.format(Locale.ROOT,
						"VWAP reversion: price was stretched %s 2 sigma %s session VWAP and snapped back %.1f ATR.",
						isLong ? "more than" : "more than", isLong ? "below" : "above", moveAtr));
			}
		}

		// Trend pullback: with-trend entry near the fast EMA.
		double ema20 = ema(context, 20);
		double ema50 = ema(context, 50);
		boolean pullbackLong = ema20 > ema50 && cutClose > ema50 && Math.abs(cutClose - ema20) <= 0.6 * atr;
		boolean pullbackShort = ema20 < ema50 && cutClose < ema50 && Math.abs(cutClose - ema20) <= 0.6 * atr;
		if ((isLong && pullbackLong) || (!isLong && pullbackShort)) {
			return new SetupInfo(SetupInfo.SetupType.TREND_PULLBACK, dir, moveAtr, String.format(Locale.ROOT,
					"Trend pullback: price rested on the 20 EMA inside an established %strend, then resumed for %.1f ATR.",
					isLong ? "up" : "down", moveAtr));
		}

		return new SetupInfo(SetupInfo.SetupType.MOMENTUM_RUN, dir, moveAtr, String.format(Locale.ROOT,
				"Momentum run: no textbook pattern at the cut, but the tape ran %.1f ATR %s from here.",
				moveAtr, isLong ? "up" : "down"));
	}

	/** {low, high} of the last complete day before the cut day, or null. */
	private static double[] previousDayExtremes(List<Bar> context, int barMinutes) {
		List<int[]> days = groupByDay(context, barMinutes);
		if (days.size() < 2) {
			return null;
		}
		int[] prev = days.get(days.size() - 2);
		double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
		for (int i = prev[0]; i <= prev[1]; i++) {
			low = Math.min(low, context.get(i).low());
			high = Math.max(high, context.get(i).high());
		}
		return new double[] { low, high };
	}

	/** {vwap, sigma} of the cut day, or null if the day is too young. */
	private static double[] sessionVwapSigma(List<Bar> context, int barMinutes) {
		List<int[]> days = groupByDay(context, barMinutes);
		int[] today = days.get(days.size() - 1);
		if (today[1] - today[0] + 1 < 6) {
			return null;
		}
		double cumV = 0, cumPV = 0, cumP2V = 0;
		for (int i = today[0]; i <= today[1]; i++) {
			Bar b = context.get(i);
			double tp = (b.high() + b.low() + b.close()) / 3;
			double v = Math.max(b.volume(), 1e-9);
			cumV += v;
			cumPV += tp * v;
			cumP2V += tp * tp * v;
		}
		double vwap = cumPV / cumV;
		double sigma = Math.sqrt(Math.max(0, cumP2V / cumV - vwap * vwap));
		return sigma > 0 ? new double[] { vwap, sigma } : null;
	}

	private static List<int[]> groupByDay(List<Bar> context, int barMinutes) {
		ZoneId tz = barMinutes == 5 ? MARKET_TZ : ZoneId.of("UTC");
		List<int[]> days = new ArrayList<>();
		int start = 0;
		for (int i = 1; i <= context.size(); i++) {
			boolean boundary = i == context.size()
					|| !context.get(i).time().atZone(tz).toLocalDate()
							.equals(context.get(start).time().atZone(tz).toLocalDate());
			if (boundary) {
				days.add(new int[] { start, i - 1 });
				start = i;
			}
		}
		return days;
	}

	private static double ema(List<Bar> bars, int period) {
		double k = 2.0 / (period + 1);
		double ema = bars.get(0).close();
		for (int i = 1; i < bars.size(); i++) {
			ema = bars.get(i).close() * k + ema * (1 - k);
		}
		return ema;
	}

	private static double atr(List<Bar> bars, int period) {
		int n = bars.size();
		int from = Math.max(1, n - period);
		double sum = 0;
		int count = 0;
		for (int i = from; i < n; i++) {
			double prevClose = bars.get(i - 1).close();
			double tr = Math.max(bars.get(i).high() - bars.get(i).low(),
					Math.max(Math.abs(bars.get(i).high() - prevClose), Math.abs(bars.get(i).low() - prevClose)));
			sum += tr;
			count++;
		}
		return count == 0 ? 0 : sum / count;
	}

	private static double round(double v) {
		return Math.round(v * 10.0) / 10.0;
	}
}
