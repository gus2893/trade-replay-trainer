package com.gusev.replaytrainer.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.learn.LearnedFilter;
import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.sim.TradeDirection;

/**
 * The live model: baseline breakout policy gated by the learned win-probability
 * filter once it has trained on enough data. Vetoed setups become SKIPs with
 * an explanation, so the human always sees why.
 */
@Component
@Primary
public class AdaptiveModelTrader implements ModelTrader {

	private static final double VETO_THRESHOLD = 0.40;

	private final MomentumBreakoutModel baseline;
	private final LearnedFilter filter;

	public AdaptiveModelTrader(MomentumBreakoutModel baseline, LearnedFilter filter) {
		this.baseline = baseline;
		this.filter = filter;
	}

	@Override
	public TradePlan proposeTrade(List<Bar> contextBars) {
		TradePlan plan = baseline.proposeTrade(contextBars);
		if (plan.direction() == TradeDirection.SKIP || !filter.ready()) {
			return plan;
		}
		double pWin = filter.winProbability(plan.features());
		Map<String, Double> features = new LinkedHashMap<>(plan.features());
		features.put("learnedPWin", Math.round(pWin * 1000.0) / 1000.0);
		String pct = Math.round(pWin * 100) + "%";
		if (pWin < VETO_THRESHOLD) {
			return TradePlan.skip(plan.rationale() + " — vetoed by learned filter (P(win) " + pct + ")", features);
		}
		return new TradePlan(plan.direction(), plan.stop(), plan.target(),
				plan.rationale() + " · learned P(win) " + pct, features);
	}
}
