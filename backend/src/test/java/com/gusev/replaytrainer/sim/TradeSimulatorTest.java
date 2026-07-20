package com.gusev.replaytrainer.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gusev.replaytrainer.market.Bar;

class TradeSimulatorTest {

	private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");

	private static Bar bar(int i, double o, double h, double l, double c) {
		return new Bar(T0.plusSeconds(300L * i), o, h, l, c, 1000);
	}

	@Test
	void longTargetHit() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, 97, 106);
		List<Bar> future = List.of(
				bar(0, 100, 102, 99, 101),
				bar(1, 101, 107, 100, 106.5));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(TradeOutcome.ExitReason.TARGET, o.exitReason());
		assertEquals(100, o.entryFill());
		assertEquals(106, o.exitPrice());
		assertEquals(2.0, o.rMultiple());
		assertEquals(1, o.exitBarIndex());
	}

	@Test
	void longStopGapFillsAtOpenNotStop() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, 97, 106);
		List<Bar> future = List.of(
				bar(0, 100, 101, 99, 99.5),
				bar(1, 95, 96, 94, 95.5));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(TradeOutcome.ExitReason.STOP, o.exitReason());
		assertEquals(95, o.exitPrice());
		assertEquals(-1.67, o.rMultiple());
	}

	@Test
	void sameBarStopAndTargetIsConservativelyStopped() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, 97, 103);
		List<Bar> future = List.of(bar(0, 100, 104, 96, 101));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(TradeOutcome.ExitReason.STOP, o.exitReason());
		assertEquals(97, o.exitPrice());
		assertEquals(-1.0, o.rMultiple());
	}

	@Test
	void shortTargetHit() {
		TradeSpec spec = new TradeSpec(TradeDirection.SHORT, 103, 94);
		List<Bar> future = List.of(
				bar(0, 100, 101, 98, 99),
				bar(1, 99, 100, 93, 93.5));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(TradeOutcome.ExitReason.TARGET, o.exitReason());
		assertEquals(94, o.exitPrice());
		assertEquals(2.0, o.rMultiple());
	}

	@Test
	void endOfDataExitsAtLastClose() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, 95, 120);
		List<Bar> future = List.of(
				bar(0, 100, 102, 99, 101),
				bar(1, 101, 103, 100, 102.5));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(TradeOutcome.ExitReason.END_OF_DATA, o.exitReason());
		assertEquals(102.5, o.exitPrice());
		assertEquals(0.5, o.rMultiple());
	}

	@Test
	void mfeAndMaeAreTracked() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, 95, 120);
		List<Bar> future = List.of(
				bar(0, 100, 110, 98, 105),
				bar(1, 105, 106, 99, 100));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(2.0, o.mfeR());
		assertEquals(-0.4, o.maeR());
		assertTrue(o.rMultiple() < o.mfeR(), "give-back means final R below peak R");
	}

	@Test
	void limitFillsOnTouchThenRunsToTarget() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, EntryType.LIMIT, 99, 97.5, 102);
		List<Bar> future = List.of(
				bar(0, 100, 101, 99.5, 100.5), // never touches 99 — order rests
				bar(1, 100.5, 100.8, 98.8, 99.2), // touch: fill at the limit
				bar(2, 99.2, 102.5, 99.0, 102.2));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(1, o.entryBarIndex());
		assertEquals(99, o.entryFill());
		assertEquals(TradeOutcome.ExitReason.TARGET, o.exitReason());
		assertEquals(2.0, o.rMultiple());
	}

	@Test
	void limitNeverTouchedExpiresUnfilled() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, EntryType.LIMIT, 95, 93, 99);
		List<Bar> future = List.of(bar(0, 100, 101, 99, 100.5), bar(1, 100.5, 102, 100, 101));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(TradeOutcome.ExitReason.NOT_FILLED, o.exitReason());
		assertEquals(0.0, o.rMultiple());
	}

	@Test
	void limitGapThroughFillsAtTheBetterOpen() {
		TradeSpec spec = new TradeSpec(TradeDirection.LONG, EntryType.LIMIT, 99, 97, 100);
		List<Bar> future = List.of(
				bar(0, 98, 99.5, 97.8, 99), // opens below the limit: price improvement
				bar(1, 99, 100.5, 98.5, 100.2));
		TradeOutcome o = TradeSimulator.simulate(spec, future);
		assertEquals(0, o.entryBarIndex());
		assertEquals(98, o.entryFill());
		assertEquals(TradeOutcome.ExitReason.TARGET, o.exitReason());
		assertEquals(2.0, o.rMultiple());
	}

	@Test
	void skipCannotBeSimulated() {
		assertThrows(IllegalArgumentException.class,
				() -> TradeSimulator.simulate(new TradeSpec(TradeDirection.SKIP, 0, 0), List.of(bar(0, 1, 1, 1, 1))));
	}
}
