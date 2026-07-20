package com.gusev.replaytrainer.scenario;

import java.util.List;

import com.gusev.replaytrainer.market.AssetClass;
import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.model.TradePlan;
import com.gusev.replaytrainer.sim.TradeOutcome;
import com.gusev.replaytrainer.sim.TradeSpec;

/**
 * Server-side state for one practice scenario. The future bars and the model's
 * plan live only here until the user commits a trade and presses play.
 */
public final class Scenario {

	public final String id;
	public final String symbol;
	public final AssetClass assetClass;
	public final int barMinutes;
	public final boolean masked;
	public final List<Bar> contextBars;
	public final List<Bar> futureBars;
	public final TradePlan modelPlan;

	private TradeSpec userTrade;
	private TradeOutcome userOutcome;
	private TradeOutcome modelOutcome;
	private boolean revealed;

	Scenario(String id, String symbol, AssetClass assetClass, int barMinutes, boolean masked,
			List<Bar> contextBars, List<Bar> futureBars, TradePlan modelPlan) {
		this.id = id;
		this.symbol = symbol;
		this.assetClass = assetClass;
		this.barMinutes = barMinutes;
		this.masked = masked;
		this.contextBars = contextBars;
		this.futureBars = futureBars;
		this.modelPlan = modelPlan;
	}

	public double lastVisibleClose() {
		return contextBars.get(contextBars.size() - 1).close();
	}

	public TradeSpec userTrade() {
		return userTrade;
	}

	public TradeOutcome userOutcome() {
		return userOutcome;
	}

	public TradeOutcome modelOutcome() {
		return modelOutcome;
	}

	public boolean revealed() {
		return revealed;
	}

	void commitTrade(TradeSpec trade) {
		if (userTrade != null) {
			throw new IllegalStateException("A trade was already placed for this scenario");
		}
		this.userTrade = trade;
	}

	void reveal(TradeOutcome userOutcome, TradeOutcome modelOutcome) {
		this.userOutcome = userOutcome;
		this.modelOutcome = modelOutcome;
		this.revealed = true;
	}
}
