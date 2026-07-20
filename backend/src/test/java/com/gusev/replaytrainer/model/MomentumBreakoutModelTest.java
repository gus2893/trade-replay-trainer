package com.gusev.replaytrainer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.testutil.TestBars;

class MomentumBreakoutModelTest {

	private final MomentumBreakoutModel model = new MomentumBreakoutModel();
	private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");

	@Test
	void steadyUptrendBreakoutGoesLongWithCoherentLevels() {
		List<Bar> bars = TestBars.path(T0, 120, Duration.ofMinutes(5), 100, 0.4, 0.05);
		TradePlan plan = model.proposeTrade(bars);
		assertEquals(TradeDirection.LONG, plan.direction());
		double close = bars.get(bars.size() - 1).close();
		assertTrue(plan.stop() < close, "stop below entry");
		assertTrue(plan.target() > close, "target above entry");
	}

	@Test
	void steadyDowntrendBreakdownGoesShort() {
		List<Bar> bars = TestBars.path(T0, 120, Duration.ofMinutes(5), 200, -0.4, 0.05);
		TradePlan plan = model.proposeTrade(bars);
		assertEquals(TradeDirection.SHORT, plan.direction());
		double close = bars.get(bars.size() - 1).close();
		assertTrue(plan.stop() > close, "stop above entry");
		assertTrue(plan.target() < close, "target below entry");
	}

	@Test
	void choppyRangeSkips() {
		// Zero drift, pure oscillation: the last close sits inside the recent range.
		List<Bar> bars = TestBars.path(T0, 118, Duration.ofMinutes(5), 100, 0, 1.5);
		TradePlan plan = model.proposeTrade(bars);
		assertEquals(TradeDirection.SKIP, plan.direction());
	}

	@Test
	void tooLittleContextSkips() {
		List<Bar> bars = TestBars.path(T0, 30, Duration.ofMinutes(5), 100, 0.4, 0.05);
		assertEquals(TradeDirection.SKIP, model.proposeTrade(bars).direction());
	}
}
