package com.gusev.replaytrainer.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.sim.TradeDirection;

/**
 * Baseline policy, v1 of the trainable model. Deterministic and explainable:
 * trade range breakouts only when the EMA trend agrees, 2R target, ATR stop.
 * Its trades plus the human good/bad/neutral ratings accumulate in the
 * training log so a learned policy can replace it later behind the same
 * interface. The feature snapshot it decided on rides along in the plan.
 */
@Component
public class MomentumBreakoutModel implements ModelTrader {

	private static final int RANGE_LOOKBACK = 20;
	private static final double STOP_ATR_MULT = 1.2;
	private static final double TARGET_R_MULT = 2.0;

	@Override
	public TradePlan proposeTrade(List<Bar> bars) {
		if (bars.size() < 60) {
			return TradePlan.skip("Not enough context bars", Map.of());
		}
		int n = bars.size();
		double close = bars.get(n - 1).close();
		double ema20 = ema(bars, 20);
		double ema50 = ema(bars, 50);
		double atr = atr(bars, 14);

		// Breakout range excludes the latest bar so its close can break it.
		double rangeHigh = Double.MIN_VALUE;
		double rangeLow = Double.MAX_VALUE;
		for (int i = n - 1 - RANGE_LOOKBACK; i < n - 1; i++) {
			rangeHigh = Math.max(rangeHigh, bars.get(i).high());
			rangeLow = Math.min(rangeLow, bars.get(i).low());
		}

		boolean trendUp = ema20 > ema50 && close > ema20;
		boolean trendDown = ema20 < ema50 && close < ema20;

		Map<String, Double> features = new LinkedHashMap<>();
		features.put("close", round(close));
		features.put("ema20", round(ema20));
		features.put("ema50", round(ema50));
		features.put("atr14", round(atr));
		features.put("rangeHigh", round(rangeHigh));
		features.put("rangeLow", round(rangeLow));
		features.put("trendUp", trendUp ? 1.0 : 0.0);
		features.put("trendDown", trendDown ? 1.0 : 0.0);

		// Full thinking rundown, so human feedback can rate the REASONING.
		String read = String.format(java.util.Locale.ROOT,
				"READ: close %.2f · ATR(14) %.3f · %d-bar range %.2f–%.2f",
				close, atr, RANGE_LOOKBACK, rangeLow, rangeHigh);
		String trend = String.format(java.util.Locale.ROOT,
				"TREND: EMA20 %.2f vs EMA50 %.2f → %s%s",
				ema20, ema50, ema20 > ema50 ? "up" : "down",
				trendUp ? ", price above EMA20 — aligned"
						: trendDown ? ", price below EMA20 — aligned"
								: ", but price is not on the trend side of EMA20 — not aligned");

		if (atr <= 0) {
			return TradePlan.skip(read + "\n" + trend + "\nTRIGGER: no measurable range on this tape → PASS", features);
		}
		if (close > rangeHigh && trendUp) {
			double stop = close - STOP_ATR_MULT * atr;
			double target = close + TARGET_R_MULT * (close - stop);
			String rationale = String.join("\n", read, trend,
					String.format(java.util.Locale.ROOT,
							"TRIGGER: close %.2f cleared the range high %.2f WITH the trend behind it → LONG", close,
							rangeHigh),
					String.format(java.util.Locale.ROOT,
							"RISK: stop %.2f (entry − 1.2×ATR, under the breakout base) · target %.2f (2R)",
							stop, target));
			return new TradePlan(TradeDirection.LONG, round(stop), round(target), rationale, features);
		}
		if (close < rangeLow && trendDown) {
			double stop = close + STOP_ATR_MULT * atr;
			double target = close - TARGET_R_MULT * (stop - close);
			String rationale = String.join("\n", read, trend,
					String.format(java.util.Locale.ROOT,
							"TRIGGER: close %.2f lost the range low %.2f WITH the trend behind it → SHORT", close,
							rangeLow),
					String.format(java.util.Locale.ROOT,
							"RISK: stop %.2f (entry + 1.2×ATR, above the breakdown base) · target %.2f (2R)",
							stop, target));
			return new TradePlan(TradeDirection.SHORT, round(stop), round(target), rationale, features);
		}
		String why;
		if (close > rangeHigh) {
			why = "TRIGGER: range high broke but the trend disagrees — counter-trend breakouts are traps → PASS";
		} else if (close < rangeLow) {
			why = "TRIGGER: range low broke but the trend disagrees — counter-trend breakdowns are traps → PASS";
		} else {
			why = "TRIGGER: close is still inside the range — no breakout, and I only trade aligned breakouts → PASS";
		}
		return TradePlan.skip(read + "\n" + trend + "\n" + why, features);
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
		return Math.round(v * 10000.0) / 10000.0;
	}
}
