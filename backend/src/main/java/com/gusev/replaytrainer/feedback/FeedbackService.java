package com.gusev.replaytrainer.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import com.gusev.replaytrainer.config.AppProperties;
import com.gusev.replaytrainer.scenario.Scenario;
import com.gusev.replaytrainer.scenario.dto.TradeReport;

/**
 * Appends one JSON line per rated scenario — the training dataset for the
 * model. Each record is self-contained: scenario identity, both trades, both
 * outcomes, and the human judgement. Bars are reproducible from the labs'
 * CSVs via symbol + cutTime, so they are not duplicated here.
 */
@Service
public class FeedbackService {

	private final Path logFile;
	private final ObjectMapper mapper;

	public FeedbackService(AppProperties props, ObjectMapper mapper) {
		this.logFile = Path.of(props.trainingLog());
		this.mapper = mapper;
	}

	public synchronized void record(Scenario scenario, FeedbackRequest feedback) {
		if (!scenario.revealed()) {
			throw new IllegalStateException("Play the scenario before rating it");
		}
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("ts", Instant.now().toString());
		record.put("scenarioId", scenario.id);
		record.put("symbol", scenario.symbol);
		record.put("assetClass", scenario.assetClass.name());
		record.put("barMinutes", scenario.barMinutes);
		record.put("cutTime", scenario.contextBars.get(scenario.contextBars.size() - 1).time().toString());
		record.put("lastClose", scenario.lastVisibleClose());
		record.put("userTrade", TradeReport.of(scenario.userTrade(), scenario.userOutcome()));
		record.put("modelTrade", TradeReport.of(scenario.modelPlan, scenario.modelOutcome()));
		record.put("rating", feedback.rating().name());
		if (feedback.note() != null && !feedback.note().isBlank()) {
			record.put("note", feedback.note());
		}
		try {
			Files.createDirectories(logFile.toAbsolutePath().getParent());
			Files.writeString(logFile, mapper.writeValueAsString(record) + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to append training record to " + logFile, e);
		}
	}
}
