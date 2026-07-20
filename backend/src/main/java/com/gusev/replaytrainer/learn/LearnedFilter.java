package com.gusev.replaytrainer.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The learned half of the model: a logistic-regression win-probability filter
 * over the baseline's feature snapshots. Trained automatically from the
 * training log (human GOOD/BAD ratings count double); weights persist to disk
 * so learning survives restarts. Deliberately simple — the point is a closed
 * loop that improves with data, behind the same ModelTrader interface.
 */
@Component
public class LearnedFilter {

	private static final Logger log = LoggerFactory.getLogger(LearnedFilter.class);
	private static final int MIN_SAMPLES = 30;
	private static final int DIM = 8;
	private static final double CLIP = 8.0;

	private final Path file;
	private final ObjectMapper mapper;
	private volatile double[] weights;
	private volatile int samples;

	public LearnedFilter(AppProperties props, ObjectMapper mapper) {
		this.file = Path.of(props.learnedModelPath());
		this.mapper = mapper;
		load();
	}

	public boolean ready() {
		return weights != null && samples >= MIN_SAMPLES;
	}

	public int samples() {
		return samples;
	}

	public double winProbability(Map<String, Double> features) {
		double[] w = weights;
		if (w == null) {
			return 0.5;
		}
		return sigmoid(dot(w, vectorize(features)));
	}

	public Map<String, Object> info() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ready", ready());
		out.put("samples", samples);
		out.put("minSamples", MIN_SAMPLES);
		return out;
	}

	/**
	 * Normalized feature vector: distances expressed in ATRs so it transfers
	 * across symbols and price levels.
	 */
	public static double[] vectorize(Map<String, Double> f) {
		double atr = f.getOrDefault("atr14", 0.0);
		if (atr <= 0) {
			atr = 1e-9;
		}
		double close = f.getOrDefault("close", 0.0);
		return new double[] {
				1.0,
				clip((close - f.getOrDefault("ema20", close)) / atr),
				clip((close - f.getOrDefault("ema50", close)) / atr),
				clip((f.getOrDefault("ema20", close) - f.getOrDefault("ema50", close)) / atr),
				clip((close - f.getOrDefault("rangeHigh", close)) / atr),
				clip((close - f.getOrDefault("rangeLow", close)) / atr),
				f.getOrDefault("trendUp", 0.0),
				f.getOrDefault("trendDown", 0.0),
		};
	}

	public static double[] vectorize(JsonNode featuresNode) {
		Map<String, Double> f = new LinkedHashMap<>();
		featuresNode.properties().forEach(e -> f.put(e.getKey(), e.getValue().asDouble(0)));
		return vectorize(f);
	}

	/** Full-batch gradient descent with L2; atomically swaps weights and persists. */
	public synchronized void train(List<double[]> x, List<Integer> y, List<Double> sampleWeight) {
		if (x.size() < MIN_SAMPLES) {
			return;
		}
		double[] w = new double[DIM];
		double lr = 0.1;
		double l2 = 1e-3;
		for (int epoch = 0; epoch < 300; epoch++) {
			double[] grad = new double[DIM];
			for (int i = 0; i < x.size(); i++) {
				double err = (sigmoid(dot(w, x.get(i))) - y.get(i)) * sampleWeight.get(i);
				for (int d = 0; d < DIM; d++) {
					grad[d] += err * x.get(i)[d];
				}
			}
			for (int d = 0; d < DIM; d++) {
				w[d] -= lr * (grad[d] / x.size() + l2 * w[d]);
			}
		}
		this.weights = w;
		this.samples = x.size();
		save();
		log.info("Learned filter retrained on {} samples", x.size());
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonNode node = mapper.readTree(Files.readString(file));
			double[] w = new double[DIM];
			for (int i = 0; i < DIM && i < node.path("w").size(); i++) {
				w[i] = node.path("w").get(i).asDouble(0);
			}
			this.weights = w;
			this.samples = node.path("samples").asInt(0);
			log.info("Loaded learned filter ({} samples)", samples);
		} catch (IOException | RuntimeException e) {
			log.warn("Could not load learned model from {}: {}", file, e.getMessage());
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("w", weights);
			out.put("samples", samples);
			Files.writeString(file, mapper.writeValueAsString(out));
		} catch (IOException e) {
			log.warn("Could not persist learned model: {}", e.getMessage());
		}
	}

	private static double clip(double v) {
		return Math.max(-CLIP, Math.min(CLIP, v));
	}

	private static double dot(double[] a, double[] b) {
		double s = 0;
		for (int i = 0; i < a.length; i++) {
			s += a[i] * b[i];
		}
		return s;
	}

	private static double sigmoid(double z) {
		return 1.0 / (1.0 + Math.exp(-z));
	}
}
