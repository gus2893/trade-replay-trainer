package com.gusev.replaytrainer.learn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gusev.replaytrainer.config.AppProperties;
import com.gusev.replaytrainer.feedback.FeedbackService;
import com.gusev.replaytrainer.scenario.CutPhase;
import com.gusev.replaytrainer.scenario.Scenario;
import com.gusev.replaytrainer.scenario.ScenarioService;

/**
 * Generates training data by itself: every few seconds it pulls a random
 * scenario, lets the model trade it, resolves the outcome, and appends it to
 * the training log (marked selfPlay). The dataset grows whether or not a
 * human is practicing; the retrainer picks it up a minute later.
 */
@Component
public class SelfPlayTrainer {

	private static final Logger log = LoggerFactory.getLogger(SelfPlayTrainer.class);

	private final AppProperties props;
	private final ScenarioService scenarios;
	private final FeedbackService feedback;

	public SelfPlayTrainer(AppProperties props, ScenarioService scenarios, FeedbackService feedback) {
		this.props = props;
		this.scenarios = scenarios;
		this.feedback = feedback;
	}

	@Scheduled(initialDelayString = "${trainer.self-play-initial-ms:20000}", fixedDelayString = "${trainer.self-play-interval-ms:15000}")
	public void tick() {
		if (!props.selfPlayEnabled()) {
			return;
		}
		try {
			Scenario scenario = scenarios.buildRandom(true, CutPhase.ANY);
			scenarios.resolveSelfPlay(scenario);
			feedback.recordOutcome(scenario, true);
		} catch (RuntimeException e) {
			log.debug("Self-play tick skipped: {}", e.getMessage());
		}
	}
}
