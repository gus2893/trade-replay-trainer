package com.gusev.replaytrainer.market;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.config.AppProperties;

import jakarta.annotation.PostConstruct;

/**
 * Discovers and parses the cached bar CSVs written by the paper-trade labs.
 * Stock files: hist_&lt;SYM&gt;_5Min_alpaca_&lt;days&gt;d.csv (5-minute RTH bars).
 * Crypto files: hist_&lt;SYM&gt;_15m_&lt;days&gt;d.csv or hist_&lt;SYM&gt;_15Min_crypto_&lt;days&gt;d.csv.
 * All share the schema: time,open,high,low,close,volume with tz-aware UTC times.
 * When a symbol appears in several files, the one with the most days wins.
 */
@Component
public class CsvBarProvider implements MarketDataProvider {

	private static final Logger log = LoggerFactory.getLogger(CsvBarProvider.class);

	private static final Pattern STOCK_FILE = Pattern.compile("hist_([A-Z0-9.\\-]+)_5Min_alpaca_(\\d+)d\\.csv");
	private static final Pattern CRYPTO_FILE = Pattern.compile("hist_([A-Z0-9.\\-]+)_(?:15m|15Min_crypto)_(\\d+)d\\.csv");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

	private record Source(Path file, AssetClass assetClass, int barMinutes, int days) {
	}

	private final AppProperties props;
	private final Map<String, Source> sources = new HashMap<>();
	private final Map<String, BarSeries> cache = new ConcurrentHashMap<>();

	public CsvBarProvider(AppProperties props) {
		this.props = props;
	}

	@PostConstruct
	void discover() {
		props.stockDataDirs().forEach(dir -> scan(Path.of(dir), STOCK_FILE, AssetClass.STOCK, 5));
		props.cryptoDataDirs().forEach(dir -> scan(Path.of(dir), CRYPTO_FILE, AssetClass.CRYPTO, 15));
		long stocks = sources.values().stream().filter(s -> s.assetClass() == AssetClass.STOCK).count();
		log.info("Discovered {} symbols ({} stocks, {} crypto)", sources.size(), stocks, sources.size() - stocks);
	}

	private void scan(Path dir, Pattern pattern, AssetClass assetClass, int barMinutes) {
		if (!Files.isDirectory(dir)) {
			log.warn("Data dir not found, skipping: {}", dir.toAbsolutePath());
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			files.forEach(file -> {
				Matcher m = pattern.matcher(file.getFileName().toString());
				if (!m.matches()) {
					return;
				}
				String symbol = m.group(1);
				int days = Integer.parseInt(m.group(2));
				Source existing = sources.get(symbol);
				if (existing == null || existing.days() < days) {
					sources.put(symbol, new Source(file, assetClass, barMinutes, days));
				}
			});
		} catch (IOException e) {
			log.warn("Failed to scan {}: {}", dir, e.getMessage());
		}
	}

	@Override
	public List<SymbolInfo> symbols() {
		return sources.entrySet().stream()
				.map(e -> new SymbolInfo(e.getKey(), e.getValue().assetClass(), e.getValue().barMinutes()))
				.sorted(Comparator.comparing(SymbolInfo::symbol))
				.toList();
	}

	@Override
	public BarSeries series(String symbol) {
		Source source = sources.get(symbol);
		if (source == null) {
			throw new java.util.NoSuchElementException("Unknown symbol: " + symbol);
		}
		return cache.computeIfAbsent(symbol, s -> parse(s, source));
	}

	private BarSeries parse(String symbol, Source source) {
		List<Bar> bars = new ArrayList<>();
		try (Stream<String> lines = Files.lines(source.file())) {
			lines.skip(1).forEach(line -> {
				String[] f = line.split(",");
				if (f.length < 6) {
					return;
				}
				try {
					bars.add(new Bar(
							OffsetDateTime.parse(f[0], TIME).toInstant(),
							Double.parseDouble(f[1]), Double.parseDouble(f[2]),
							Double.parseDouble(f[3]), Double.parseDouble(f[4]),
							Double.parseDouble(f[5])));
				} catch (RuntimeException ignored) {
					// tolerate an occasional malformed row rather than failing the series
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + source.file(), e);
		}
		bars.sort(Comparator.comparing(Bar::time));
		return new BarSeries(symbol, source.assetClass(), source.barMinutes(), List.copyOf(bars));
	}
}
