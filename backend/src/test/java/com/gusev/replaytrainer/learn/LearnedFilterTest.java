package com.gusev.replaytrainer.learn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gusev.replaytrainer.config.AppProperties;

import tools.jackson.databind.ObjectMapper;

class LearnedFilterTest {

	@TempDir
	Path dir;

	private LearnedFilter freshFilter() {
		AppProperties props = new AppProperties(List.of(), List.of(), "unused", List.of(), false,
				dir.resolve("model.json").toString(), null, null, null, null);
		return new LearnedFilter(props, new ObjectMapper());
	}

	private static Map<String, Double> features(double emaSpreadAtr) {
		// A breakout snapshot whose quality is encoded in the EMA spread.
		return Map.of(
				"close", 100.0,
				"ema20", 100.0 - emaSpreadAtr,
				"ema50", 100.0 - 2 * emaSpreadAtr,
				"atr14", 1.0,
				"rangeHigh", 99.5,
				"rangeLow", 97.0,
				"trendUp", emaSpreadAtr > 0 ? 1.0 : 0.0,
				"trendDown", emaSpreadAtr > 0 ? 0.0 : 1.0);
	}

	@Test
	void learnsASeparablePattern() {
		LearnedFilter filter = freshFilter();
		assertFalse(filter.ready(), "untrained filter must not gate trades");

		List<double[]> x = new ArrayList<>();
		List<Integer> y = new ArrayList<>();
		List<Double> w = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			boolean strongTrend = i % 2 == 0;
			x.add(LearnedFilter.vectorize(features(strongTrend ? 2.0 : -2.0)));
			y.add(strongTrend ? 1 : 0);
			w.add(1.0);
		}
		filter.train(x, y, w);

		assertTrue(filter.ready());
		double pStrong = filter.winProbability(features(2.0));
		double pWeak = filter.winProbability(features(-2.0));
		assertTrue(pStrong > 0.7, "strong-trend breakout should score high, was " + pStrong);
		assertTrue(pWeak < 0.3, "counter-trend breakout should score low, was " + pWeak);
	}

	@Test
	void persistsAndReloadsWeights() {
		LearnedFilter filter = freshFilter();
		List<double[]> x = new ArrayList<>();
		List<Integer> y = new ArrayList<>();
		List<Double> w = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			x.add(LearnedFilter.vectorize(features(i % 2 == 0 ? 1.5 : -1.5)));
			y.add(i % 2 == 0 ? 1 : 0);
			w.add(1.0);
		}
		filter.train(x, y, w);

		LearnedFilter reloaded = freshFilter();
		assertTrue(reloaded.ready(), "weights should survive a restart");
		assertTrue(reloaded.winProbability(features(1.5)) > 0.5);
	}
}
