package com.gusev.replaytrainer.market;

import java.util.List;

public interface MarketDataProvider {

	List<SymbolInfo> symbols();

	/** Full cached series for a symbol, oldest bar first. */
	BarSeries series(String symbol);
}
