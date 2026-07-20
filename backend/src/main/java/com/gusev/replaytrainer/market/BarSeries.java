package com.gusev.replaytrainer.market;

import java.util.List;

public record BarSeries(String symbol, AssetClass assetClass, int barMinutes, List<Bar> bars) {

	public String timeframe() {
		return barMinutes + "m";
	}
}
