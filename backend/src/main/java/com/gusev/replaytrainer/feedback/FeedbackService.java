package com.gusev.replaytrainer.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.gusev.replaytrainer.config.AppProperties;
import com.gusev.replaytrainer.scenario.Scenario;
import com.gusev.replaytrainer.scenario.dto.TradeReport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The training dataset, one JSON line per event. Two record types joined by
 * scenarioId:
 * "outcome" — written for EVERY played scenario (whether or not the human
 * bothers to rate it): both trades, both results, and the model's feature
 * snapshot at decision time. This is what a learned policy trains on.
 * "rating" — the human GOOD/BAD/NEUTRAL judgement, when given.
 * Bars stay reproducible from the labs' CSVs via symbol + cutTime.
 */
@Service
public class FeedbackService {

	private final Path logFile;
	private final ObjectMapper mapper;

	public FeedbackService(AppProperties props, ObjectMapper mapper) {
		this.logFile = Path.of(props.trainingLog());
		this.mapper = mapper;
	}

	/** Logs the played-out scenario. Idempotent per scenario. */
	public void recordOutcome(Scenario scenario) {
		if (!scenario.revealed() || !scenario.markOutcomeLogged()) {
			return;
		}
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("type", "outcome");
		record.put("ts", Instant.now().toString());
		record.put("scenarioId", scenario.id);
		record.put("symbol", scenario.symbol);
		record.put("assetClass", scenario.assetClass.name());
		record.put("barMinutes", scenario.barMinutes);
		record.put("cutTime", scenario.contextBars.get(scenario.contextBars.size() - 1).time().toString());
		record.put("lastClose", scenario.lastVisibleClose());
		record.put("userTrade", TradeReport.of(scenario.userTrade(), scenario.userOutcome()));
		record.put("modelTrade", TradeReport.of(scenario.modelPlan, scenario.modelOutcome()));
		record.put("modelFeatures", scenario.modelPlan.features());
		append(record);
	}

	public void recordRating(Scenario scenario, FeedbackRequest feedback) {
		if (!scenario.revealed()) {
			throw new IllegalStateException("Play the scenario before rating it");
		}
		recordOutcome(scenario); // safety net: rating always has its outcome row
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("type", "rating");
		record.put("ts", Instant.now().toString());
		record.put("scenarioId", scenario.id);
		record.put("symbol", scenario.symbol);
		record.put("rating", feedback.rating().name());
		if (feedback.note() != null && !feedback.note().isBlank()) {
			record.put("note", feedback.note());
		}
		append(record);
	}

	private synchronized void append(Map<String, Object> record) {
		try {
			Files.createDirectories(logFile.toAbsolutePath().getParent());
			Files.writeString(logFile, mapper.writeValueAsString(record) + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to append training record to " + logFile, e);
		}
	}

	/** Dataset summary so you can watch the training set grow. */
	public Map<String, Object> stats() {
		int outcomes = 0, ratings = 0, modelTrades = 0, userTrades = 0;
		double modelR = 0, userR = 0;
		Map<String, Integer> ratingCounts = new LinkedHashMap<>(Map.of("GOOD", 0, "NEUTRAL", 0, "BAD", 0));
		List<String> lines = readLines();
		for (String line : lines) {
			JsonNode node;
			try {
				node = mapper.readTree(line);
			} catch (RuntimeException e) {
				continue;
			}
			String type = node.path("type").asString("outcome");
			if (type.equals("rating")) {
				ratings++;
				ratingCounts.merge(node.path("rating").asString(""), 1, Integer::sum);
			} else {
				outcomes++;
				JsonNode model = node.path("modelTrade");
				if (!model.path("direction").asString("SKIP").equals("SKIP")) {
					modelTrades++;
					modelR += model.path("outcome").path("r").asDouble(0);
				}
				JsonNode user = node.path("userTrade");
				if (!user.path("direction").asString("SKIP").equals("SKIP")) {
					userTrades++;
					userR += user.path("outcome").path("r").asDouble(0);
				}
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("outcomes", outcomes);
		out.put("ratings", ratings);
		out.put("ratingCounts", ratingCounts);
		out.put("modelTrades", modelTrades);
		out.put("modelAvgR", modelTrades == 0 ? 0 : round(modelR / modelTrades));
		out.put("userTrades", userTrades);
		out.put("userAvgR", userTrades == 0 ? 0 : round(userR / userTrades));
		out.put("logFile", logFile.toAbsolutePath().toString());
		return out;
	}

	private List<String> readLines() {
		try {
			return Files.exists(logFile) ? Files.readAllLines(logFile, StandardCharsets.UTF_8) : new ArrayList<>();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + logFile, e);
		}
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
