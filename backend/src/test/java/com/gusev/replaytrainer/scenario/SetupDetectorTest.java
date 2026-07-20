package com.gusev.replaytrainer.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.sim.TradeDirection;

class SetupDetectorTest {

	private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");

	private static Bar bar(int i, double o, double h, double l, double c) {
		return new Bar(T0.plusSeconds(300L * i), o, h, l, c, 1000);
	}

	/** Flat tape around 100 with ~0.6 range bars. */
	private static List<Bar> flatContext(int n) {
		List<Bar> bars = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			bars.add(bar(i, 100, 100.3, 99.7, 100));
		}
		return bars;
	}

	@Test
	void sweepOfTheSwingLowThatRipsIsALiquiditySweepLong() {
		List<Bar> context = flatContext(60);
		context.set(30, bar(30, 100, 100.3, 99.0, 100)); // the swing low resting at 99.0
		context.set(57, bar(57, 100, 100.2, 98.5, 99.9)); // the sweep: poked below 99.0
		context.set(59, bar(59, 100, 100.3, 99.8, 100.2)); // cut closes back above the level

		List<Bar> future = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			double base = 100.2 + i * 0.08;
			future.add(bar(60 + i, base, base + 0.15, Math.max(99.9, base - 0.1), base + 0.08));
		}

		SetupInfo setup = SetupDetector.classify(context, future, 5);
		assertEquals(SetupInfo.SetupType.LIQUIDITY_SWEEP, setup.type());
		assertEquals(TradeDirection.LONG, setup.direction());
	}

	@Test
	void quietTapeAfterTheCutIsChop() {
		List<Bar> context = flatContext(60);
		List<Bar> future = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			future.add(bar(60 + i, 100, 100.4, 99.6, 100));
		}
		SetupInfo setup = SetupDetector.classify(context, future, 5);
		assertEquals(SetupInfo.SetupType.CHOP, setup.type());
		assertEquals(TradeDirection.SKIP, setup.direction());
	}

	@Test
	void rangeBreakWithFollowThroughIsBreakoutContinuation() {
		List<Bar> context = flatContext(60);
		context.set(59, bar(59, 100, 101.1, 99.9, 101.0)); // cut closes above the 20-bar range high

		List<Bar> future = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			double base = 101.0 + i * 0.07;
			future.add(bar(60 + i, base, base + 0.15, Math.max(100.5, base - 0.1), base + 0.07));
		}

		SetupInfo setup = SetupDetector.classify(context, future, 5);
		assertEquals(SetupInfo.SetupType.BREAKOUT_CONTINUATION, setup.type());
		assertEquals(TradeDirection.LONG, setup.direction());
	}
}
