package com.gusev.replaytrainer.learn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.feedback.FeedbackService;

import tools.jackson.databind.JsonNode;

/**
 * Closes the learning loop automatically: every minute, rebuild the training
 * set from the JSONL log (model trades with outcomes; human ratings weight
 * their samples double and override the label) and refit the learned filter.
 * No manual training step ever needs to run.
 */
@Component
public class ModelRetrainer {

	private final FeedbackService feedback;
	private final LearnedFilter filter;

	public ModelRetrainer(FeedbackService feedback, LearnedFilter filter) {
		this.feedback = feedback;
		this.filter = filter;
	}

	@Scheduled(initialDelayString = "${trainer.retrain-initial-ms:15000}", fixedDelayString = "${trainer.retrain-interval-ms:60000}")
	public void retrain() {
		List<JsonNode> records = feedback.readRecords();
		Map<String, String> ratings = new HashMap<>();
		for (JsonNode r : records) {
			if ("rating".equals(r.path("type").asString(""))) {
				ratings.put(r.path("scenarioId").asString(""), r.path("rating").asString(""));
			}
		}
		List<double[]> x = new ArrayList<>();
		List<Integer> y = new ArrayList<>();
		List<Double> wt = new ArrayList<>();
		for (JsonNode r : records) {
			if ("rating".equals(r.path("type").asString("")) || "managed".equals(r.path("type").asString(""))) {
				continue;
			}
			JsonNode model = r.path("modelTrade");
			JsonNode features = r.path("modelFeatures");
			if (model.path("direction").asString("SKIP").equals("SKIP") || features.isMissingNode()
					|| model.path("outcome").isMissingNode() || model.path("outcome").isNull()) {
				continue;
			}
			int label = model.path("outcome").path("r").asDouble(0) > 0 ? 1 : 0;
			double weight = 1.0;
			String rating = ratings.get(r.path("scenarioId").asString(""));
			if ("GOOD".equals(rating)) {
				label = 1;
				weight = 2.0;
			} else if ("BAD".equals(rating)) {
				label = 0;
				weight = 2.0;
			}
			x.add(LearnedFilter.vectorize(features));
			y.add(label);
			wt.add(weight);
		}
		filter.train(x, y, wt);
	}
}
