package com.gusev.replaytrainer.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gusev.replaytrainer.config.AppProperties;
import com.gusev.replaytrainer.testutil.TestBars;

class CsvBarProviderTest {

	@TempDir
	Path stocksDir;
	@TempDir
	Path cryptoDir;

	private CsvBarProvider provider;

	@BeforeEach
	void setUp() throws IOException {
		Files.writeString(stocksDir.resolve("hist_TSLA_5Min_alpaca_400d.csv"),
				TestBars.toCsv(TestBars.stockSeries(3, Instant.parse("2026-01-05T14:30:00Z"), 100, 0.05)));
		Files.writeString(stocksDir.resolve("hist_TSLA_5Min_alpaca_190d.csv"),
				TestBars.toCsv(TestBars.stockSeries(1, Instant.parse("2026-01-05T14:30:00Z"), 100, 0.05)));
		Files.writeString(stocksDir.resolve("not_a_bar_file.csv"), "a,b\n1,2\n");
		Files.writeString(cryptoDir.resolve("hist_BTC_15m_365d.csv"),
				TestBars.toCsv(TestBars.cryptoSeries(50, Instant.parse("2026-01-01T00:00:00Z"), 60000, 5)));

		provider = new CsvBarProvider(new AppProperties(
				List.of(stocksDir.toString()), List.of(cryptoDir.toString()), "unused", List.of(), false, null,
				null, null, null, null));
		provider.discover();
	}

	@Test
	void discoversSymbolsAcrossBothAssetClasses() {
		List<SymbolInfo> symbols = provider.symbols();
		assertEquals(2, symbols.size());
		assertEquals(new SymbolInfo("BTC", AssetClass.CRYPTO, 15), symbols.get(0));
		assertEquals(new SymbolInfo("TSLA", AssetClass.STOCK, 5), symbols.get(1));
	}

	@Test
	void prefersTheDeepestHistoryFile() {
		// 400d file has 3 sessions x 78 bars; the 190d file only one session.
		assertEquals(3 * 78, provider.series("TSLA").bars().size());
	}

	@Test
	void parsesTheLabsCsvFormat() {
		BarSeries btc = provider.series("BTC");
		Bar first = btc.bars().get(0);
		assertEquals(Instant.parse("2026-01-01T00:00:00Z"), first.time());
		assertEquals(60000, first.open());
		assertEquals(50, btc.bars().size());
		for (int i = 1; i < btc.bars().size(); i++) {
			assertTrue(btc.bars().get(i - 1).time().isBefore(btc.bars().get(i).time()), "bars sorted by time");
		}
	}

	@Test
	void unknownSymbolThrows() {
		assertThrows(NoSuchElementException.class, () -> provider.series("NOPE"));
	}
}
