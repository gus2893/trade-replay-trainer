package com.gusev.replaytrainer.cloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.config.AppProperties;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cloud mode (trainer.bootstrap=true): the host has no lab CSVs, so fetch the
 * bars at startup and write them in the labs' exact CSV format. Stocks come
 * from Alpaca's data API (keys via APCA_API_KEY_ID / APCA_API_SECRET_KEY env
 * vars — never in the repo), crypto from Coinbase's public API (no key).
 * Existing files are kept, so a warm disk skips straight to serving.
 */
@Component("cloudDataBootstrap")
public class CloudDataBootstrap {

	private static final Logger log = LoggerFactory.getLogger(CloudDataBootstrap.class);
	private static final ZoneId ET = ZoneId.of("America/New_York");
	private static final LocalTime RTH_OPEN = LocalTime.of(9, 30);
	private static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);
	private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final int CRYPTO_DAYS = 365;
	private static final String[] CRYPTO_PRODUCTS = { "BTC-USD", "ETH-USD" };
	private static final int FOREX_DAYS = 730;
	private static final String[] FOREX_PAIRS = { "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD" };
	private static final int YAHOO_STOCK_DAYS = 60;

	private final AppProperties props;
	private final ObjectMapper mapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private String alpacaFeed = "sip";

	public CloudDataBootstrap(AppProperties props, ObjectMapper mapper) {
		this.props = props;
		this.mapper = mapper;
	}

	@PostConstruct
	void run() {
		if (!props.bootstrapEnabled()) {
			return;
		}
		Path dir = Path.of(props.stockDataDirs().get(0));
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot create data dir " + dir, e);
		}
		fetchCrypto(dir);
		fetchForex(dir);
		fetchStocks(dir);
	}

	/* ── Yahoo Finance (public, no key) ────────────────────── */

	/** Forex majors: hourly bars, up to 2 years — Yahoo's deepest free intraday. */
	private void fetchForex(Path dir) {
		for (String pair : FOREX_PAIRS) {
			Path file = dir.resolve("hist_" + pair + "_60m_yahoo_" + FOREX_DAYS + "d.csv");
			if (Files.exists(file)) {
				continue;
			}
			try {
				List<String> rows = fetchYahoo(pair + "=X", "60m", "730d");
				writeCsv(file, rows);
				log.info("Bootstrapped {} ({} bars)", file.getFileName(), rows.size());
			} catch (Exception e) {
				log.warn("Forex bootstrap failed for {}: {}", pair, e.getMessage());
			}
		}
	}

	/** Keyless stock fallback: Yahoo caps 5-minute bars at ~60 days. */
	private void fetchStocksFromYahoo(Path dir) {
		for (String symbol : props.symbolsOrEmpty()) {
			Path file = dir.resolve("hist_" + symbol + "_5Min_yahoo_" + YAHOO_STOCK_DAYS + "d.csv");
			if (Files.exists(file)) {
				continue;
			}
			try {
				List<String> rows = fetchYahoo(symbol, "5m", "60d");
				writeCsv(file, rows);
				log.info("Bootstrapped {} ({} bars)", file.getFileName(), rows.size());
			} catch (Exception e) {
				log.warn("Yahoo stock bootstrap failed for {}: {}", symbol, e.getMessage());
			}
		}
	}

	private List<String> fetchYahoo(String yahooSymbol, String interval, String range)
			throws IOException, InterruptedException {
		String url = String.format(
				"https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=%s&range=%s", yahooSymbol, interval,
				range);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", "Mozilla/5.0 (TapeDojo replay trainer)")
				.build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() >= 400) {
			throw new IOException("Yahoo HTTP " + res.statusCode());
		}
		JsonNode result = mapper.readTree(res.body()).path("chart").path("result").get(0);
		JsonNode ts = result.path("timestamp");
		JsonNode quote = result.path("indicators").path("quote").get(0);
		List<String> rows = new ArrayList<>();
		for (int i = 0; i < ts.size(); i++) {
			JsonNode o = quote.path("open").get(i);
			JsonNode h = quote.path("high").get(i);
			JsonNode l = quote.path("low").get(i);
			JsonNode c = quote.path("close").get(i);
			if (o == null || o.isNull() || h.isNull() || l.isNull() || c.isNull()) {
				continue; // Yahoo pads gaps with nulls
			}
			double v = quote.path("volume").get(i).isNull() ? 0 : quote.path("volume").get(i).asDouble();
			rows.add(csvRow(Instant.ofEpochSecond(ts.get(i).asLong()),
					o.asDouble(), h.asDouble(), l.asDouble(), c.asDouble(), v));
		}
		Thread.sleep(400); // be polite to the unofficial endpoint
		return rows;
	}

	/* ── Coinbase (public, no key) ─────────────────────────── */

	private void fetchCrypto(Path dir) {
		for (String product : CRYPTO_PRODUCTS) {
			String symbol = product.substring(0, product.indexOf('-'));
			Path file = dir.resolve("hist_" + symbol + "_15m_" + CRYPTO_DAYS + "d.csv");
			if (Files.exists(file)) {
				continue;
			}
			try {
				List<String> rows = new ArrayList<>();
				long step = 900L;
				long end = Instant.now().getEpochSecond() / step * step;
				long start = end - CRYPTO_DAYS * 86400L;
				long cursor = end;
				while (cursor > start) {
					long pageStart = Math.max(start, cursor - 300 * step);
					String url = String.format(
							"https://api.exchange.coinbase.com/products/%s/candles?granularity=900&start=%s&end=%s",
							product, Instant.ofEpochSecond(pageStart), Instant.ofEpochSecond(cursor));
					JsonNode page = getJson(url, null, null);
					// Coinbase rows: [time, low, high, open, close, volume], newest first.
					for (JsonNode row : page) {
						rows.add(csvRow(Instant.ofEpochSecond(row.get(0).asLong()),
								row.get(3).asDouble(), row.get(2).asDouble(),
								row.get(1).asDouble(), row.get(4).asDouble(), row.get(5).asDouble()));
					}
					cursor = pageStart;
					Thread.sleep(150);
				}
				rows.sort(String::compareTo);
				writeCsv(file, rows);
				log.info("Bootstrapped {} ({} bars)", file.getFileName(), rows.size());
			} catch (Exception e) {
				log.warn("Crypto bootstrap failed for {}: {}", product, e.getMessage());
			}
		}
	}

	/* ── Alpaca stocks (keys via env) ──────────────────────── */

	private void fetchStocks(Path dir) {
		if (props.symbolsOrEmpty().isEmpty()) {
			return;
		}
		String key = System.getenv("APCA_API_KEY_ID");
		String secret = System.getenv("APCA_API_SECRET_KEY");
		if (key == null || secret == null) {
			log.info("No Alpaca keys — falling back to Yahoo for stocks (5m bars, ~{}d of history)",
					YAHOO_STOCK_DAYS);
			fetchStocksFromYahoo(dir);
			return;
		}
		int days = props.backfillDaysOrDefault();
		Instant start = Instant.now().minus(Duration.ofDays(days));
		for (String symbol : props.symbolsOrEmpty()) {
			Path file = dir.resolve("hist_" + symbol + "_5Min_alpaca_" + days + "d.csv");
			if (Files.exists(file)) {
				continue;
			}
			try {
				List<String> rows = new ArrayList<>();
				String pageToken = null;
				do {
					String url = String.format(
							"https://data.alpaca.markets/v2/stocks/%s/bars?timeframe=5Min&adjustment=all&limit=10000&feed=%s&start=%s",
							symbol, alpacaFeed, start)
							+ (pageToken != null ? "&page_token=" + pageToken : "");
					JsonNode json;
					try {
						json = getJson(url, key, secret);
					} catch (HttpStatusException e) {
						if (e.status == 403 && alpacaFeed.equals("sip")) {
							alpacaFeed = "iex"; // free-tier entitlement fallback, same as the lab
							continue;
						}
						throw e;
					}
					for (JsonNode bar : json.path("bars")) {
						Instant t = Instant.parse(bar.path("t").asString(""));
						LocalTime et = ZonedDateTime.ofInstant(t, ET).toLocalTime();
						if (et.isBefore(RTH_OPEN) || !et.isBefore(RTH_CLOSE)) {
							continue; // RTH only, matching the lab's cache
						}
						rows.add(csvRow(t, bar.path("o").asDouble(), bar.path("h").asDouble(),
								bar.path("l").asDouble(), bar.path("c").asDouble(), bar.path("v").asDouble()));
					}
					pageToken = json.path("next_page_token").isNull() ? null
							: json.path("next_page_token").asString(null);
					Thread.sleep(250);
				} while (pageToken != null);
				writeCsv(file, rows);
				log.info("Bootstrapped {} ({} bars)", file.getFileName(), rows.size());
			} catch (Exception e) {
				log.warn("Stock bootstrap failed for {}: {}", symbol, e.getMessage());
			}
		}
	}

	/* ── helpers ───────────────────────────────────────────── */

	private String csvRow(Instant t, double o, double h, double l, double c, double v) {
		return ZonedDateTime.ofInstant(t, ZoneId.of("UTC")).format(CSV_TIME) + "+00:00,"
				+ o + "," + h + "," + l + "," + c + "," + v;
	}

	private void writeCsv(Path file, List<String> rows) throws IOException {
		StringBuilder sb = new StringBuilder("time,open,high,low,close,volume\n");
		rows.forEach(r -> sb.append(r).append('\n'));
		Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
	}

	private JsonNode getJson(String url, String key, String secret) throws IOException, InterruptedException {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
		if (key != null) {
			req.header("APCA-API-KEY-ID", key).header("APCA-API-SECRET-KEY", secret);
		}
		HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() >= 400) {
			throw new HttpStatusException(res.statusCode(), res.body());
		}
		return mapper.readTree(res.body());
	}

	private static final class HttpStatusException extends IOException {
		final int status;

		HttpStatusException(int status, String body) {
			super("HTTP " + status + ": " + (body.length() > 200 ? body.substring(0, 200) : body));
			this.status = status;
		}
	}
}
