package com.gusev.replaytrainer.model;

import java.util.List;

import com.gusev.replaytrainer.market.Bar;

/**
 * A trading policy that sees exactly what the human sees: the context bars up
 * to the cut, nothing after. Implementations must not receive future bars.
 */
public interface ModelTrader {

	TradePlan proposeTrade(List<Bar> contextBars);
}
