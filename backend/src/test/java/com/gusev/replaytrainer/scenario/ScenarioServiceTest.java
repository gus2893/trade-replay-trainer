package com.gusev.replaytrainer.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.gusev.replaytrainer.market.AssetClass;
import com.gusev.replaytrainer.market.Bar;
import com.gusev.replaytrainer.market.BarSeries;
import com.gusev.replaytrainer.market.MarketDataProvider;
import com.gusev.replaytrainer.market.SymbolInfo;
import com.gusev.replaytrainer.model.MomentumBreakoutModel;
import com.gusev.replaytrainer.sim.TradeDirection;
import com.gusev.replaytrainer.sim.TradeSpec;
import com.gusev.replaytrainer.testutil.TestBars;

class ScenarioServiceTest {

	private static final ZoneId ET = ZoneId.of("America/New_York");

	private static final class FakeProvider implements MarketDataProvider {
		private final Map<String, BarSeries> series = Map.of(
				"TSLA", new BarSeries("TSLA", AssetClass.STOCK, 5,
						TestBars.stockSeries(6, Instant.parse("2026-01-05T14:30:00Z"), 100, 0.05)),
				"BTC", new BarSeries("BTC", AssetClass.CRYPTO, 15,
						TestBars.cryptoSeries(500, Instant.parse("2026-01-01T00:00:00Z"), 60000, 5)));

		@Override
		public List<SymbolInfo> symbols() {
			return series.values().stream()
					.map(s -> new SymbolInfo(s.symbol(), s.assetClass(), s.barMinutes()))
					.toList();
		}

		@Override
		public BarSeries series(String symbol) {
			BarSeries s = series.get(symbol);
			if (s == null) {
				throw new NoSuchElementException("Unknown symbol: " + symbol);
			}
			return s;
		}
	}

	private ScenarioService service(long seed) {
		return new ScenarioService(new FakeProvider(), new MomentumBreakoutModel(), new Random(seed));
	}

	@Test
	void randomScenariosNeverLeakTheFuture() {
		ScenarioService service = service(42);
		for (int i = 0; i < 30; i++) {
			Scenario s = service.create(null, true);
			assertTrue(s.masked, "random pick must be masked");
			assertFalse(s.contextBars.isEmpty());
			assertFalse(s.futureBars.isEmpty());
			Bar lastVisible = s.contextBars.get(s.contextBars.size() - 1);
			assertTrue(lastVisible.time().isBefore(s.futureBars.get(0).time()),
					"context must end strictly before the future starts");
			assertTrue(s.contextBars.size() >= 60, "model needs enough context");
			assertNotNull(s.setup, "every scenario is classified");
			assertTrue(s.futureBars.size() <= s.contextBars.size(),
					"future plays out at most as long as the shown context");
			int minFuture = s.assetClass == AssetClass.STOCK ? 12 : 48;
			assertTrue(s.futureBars.size() >= minFuture, "future long enough to resolve a trade");
		}
	}

	@Test
	void openPhaseCutsLandInTheFirstNinetyMinutes() {
		ScenarioService service = service(11);
		for (int i = 0; i < 20; i++) {
			Scenario s = service.create(null, false, CutPhase.OPEN);
			Bar cut = s.contextBars.get(s.contextBars.size() - 1);
			long barsIntoSession = s.contextBars.stream()
					.filter(b -> b.time().atZone(ET).toLocalDate().equals(cut.time().atZone(ET).toLocalDate()))
					.count();
			assertTrue(barsIntoSession - 1 <= 18,
					"OPEN cut must land within the first 90 minutes, was bar " + (barsIntoSession - 1));
		}
	}

	@Test
	void selfPlayScenariosResolveWithoutBeingStored() {
		ScenarioService service = service(13);
		Scenario s = service.buildRandom(true, CutPhase.ANY);
		service.resolveSelfPlay(s);
		assertTrue(s.revealed());
		assertNull(s.userOutcome(), "self-play user passes");
		assertThrows(NoSuchElementException.class, () -> service.get(s.id), "ephemeral scenario is not stored");
	}

	@Test
	void specificSymbolIsHonoredAndUnmasked() {
		Scenario s = service(1).create("TSLA", false);
		assertEquals("TSLA", s.symbol);
		assertFalse(s.masked);
	}

	@Test
	void cryptoToggleExcludesCrypto() {
		ScenarioService service = service(7);
		for (int i = 0; i < 40; i++) {
			assertEquals(AssetClass.STOCK, service.create(null, false).assetClass);
		}
	}

	@Test
	void unknownSymbolIs404() {
		assertThrows(NoSuchElementException.class, () -> service(1).create("NOPE", true));
	}

	@Test
	void fullTradeFlowProducesOutcomes() {
		ScenarioService service = service(3);
		Scenario s = service.create("TSLA", false);
		double close = s.lastVisibleClose();
		service.placeTrade(s.id, new TradeSpec(TradeDirection.LONG, close * 0.98, close * 1.03));
		Scenario played = service.play(s.id);
		assertTrue(played.revealed());
		assertNotNull(played.userOutcome());
		if (played.modelPlan.direction() == TradeDirection.SKIP) {
			assertNull(played.modelOutcome());
		} else {
			assertNotNull(played.modelOutcome());
		}
	}

	@Test
	void skippingIsAValidAnswer() {
		ScenarioService service = service(4);
		Scenario s = service.create("TSLA", false);
		service.placeTrade(s.id, new TradeSpec(TradeDirection.SKIP, 0, 0));
		assertNull(service.play(s.id).userOutcome());
	}

	@Test
	void invalidStopsAreRejected() {
		ScenarioService service = service(5);
		Scenario s = service.create("TSLA", false);
		double close = s.lastVisibleClose();
		assertThrows(IllegalArgumentException.class,
				() -> service.placeTrade(s.id, new TradeSpec(TradeDirection.LONG, close * 1.01, close * 1.05)));
	}

	@Test
	void playBeforeTradeIsRejected() {
		ScenarioService service = service(6);
		Scenario s = service.create("TSLA", false);
		assertThrows(IllegalStateException.class, () -> service.play(s.id));
	}
}
