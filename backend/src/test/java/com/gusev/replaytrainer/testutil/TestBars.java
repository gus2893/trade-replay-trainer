package com.gusev.replaytrainer.testutil;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.gusev.replaytrainer.market.Bar;

/** Deterministic synthetic bar builders matching the labs' CSV conventions. */
public final class TestBars {

	private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private TestBars() {
	}

	/** One RTH stock session: 78 five-minute bars from 13:30 UTC (9:30 ET in summer). */
	public static List<Bar> stockSession(Instant sessionOpen, double startPrice, double driftPerBar) {
		return path(sessionOpen, 78, Duration.ofMinutes(5), startPrice, driftPerBar, 0.6);
	}

	/** Several consecutive weekday sessions with a continuous price path. */
	public static List<Bar> stockSeries(int sessions, Instant firstOpen, double startPrice, double driftPerBar) {
		List<Bar> bars = new ArrayList<>();
		double price = startPrice;
		Instant open = firstOpen;
		for (int s = 0; s < sessions; s++) {
			List<Bar> day = stockSession(open, price, driftPerBar);
			bars.addAll(day);
			price = day.get(day.size() - 1).close();
			open = open.plus(Duration.ofDays(1));
		}
		return bars;
	}

	/** Continuous 15-minute crypto bars. */
	public static List<Bar> cryptoSeries(int count, Instant start, double startPrice, double driftPerBar) {
		return path(start, count, Duration.ofMinutes(15), startPrice, driftPerBar, 40);
	}

	/** Deterministic wobbly path: close = open + drift + amplitude * sin. */
	public static List<Bar> path(Instant start, int count, Duration step, double startPrice,
			double driftPerBar, double amplitude) {
		List<Bar> bars = new ArrayList<>();
		double price = startPrice;
		for (int i = 0; i < count; i++) {
			double close = price + driftPerBar + amplitude * Math.sin(i * 0.7);
			double high = Math.max(price, close) + amplitude * 0.3;
			double low = Math.min(price, close) - amplitude * 0.3;
			bars.add(new Bar(start.plus(step.multipliedBy(i)), r(price), r(high), r(low), r(close), 1000 + i));
			price = close;
		}
		return bars;
	}

	/** Serializes bars exactly like the labs' CSVs (tz-aware UTC "+00:00" suffix). */
	public static String toCsv(List<Bar> bars) {
		StringBuilder sb = new StringBuilder("time,open,high,low,close,volume\n");
		for (Bar b : bars) {
			sb.append(OffsetDateTime.ofInstant(b.time(), ZoneOffset.UTC).format(CSV_TIME))
					.append("+00:00,")
					.append(b.open()).append(',').append(b.high()).append(',')
					.append(b.low()).append(',').append(b.close()).append(',')
					.append((long) b.volume()).append('\n');
		}
		return sb.toString();
	}

	private static double r(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}
}
