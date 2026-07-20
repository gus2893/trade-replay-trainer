package com.gusev.replaytrainer.web;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gusev.replaytrainer.feedback.FeedbackRequest;
import com.gusev.replaytrainer.feedback.FeedbackService;
import com.gusev.replaytrainer.scenario.ScenarioService;
import com.gusev.replaytrainer.scenario.dto.NewScenarioRequest;
import com.gusev.replaytrainer.scenario.dto.RevealResponse;
import com.gusev.replaytrainer.scenario.dto.ScenarioResponse;
import com.gusev.replaytrainer.scenario.dto.TradeRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

	private final ScenarioService scenarios;
	private final FeedbackService feedback;

	public ScenarioController(ScenarioService scenarios, FeedbackService feedback) {
		this.scenarios = scenarios;
		this.feedback = feedback;
	}

	@PostMapping
	public ScenarioResponse create(@RequestBody(required = false) NewScenarioRequest request) {
		NewScenarioRequest req = request == null ? new NewScenarioRequest(null, true) : request;
		return ScenarioResponse.from(scenarios.create(req.symbol(), req.cryptoAllowed()));
	}

	@PostMapping("/{id}/trade")
	public Map<String, Object> trade(@PathVariable String id, @Valid @RequestBody TradeRequest request) {
		scenarios.placeTrade(id, request.toSpec());
		// The model's plan was fixed at scenario creation; only its existence is
		// disclosed here — details stay hidden until play.
		return Map.of("accepted", true, "modelReady", true);
	}

	@PostMapping("/{id}/play")
	public RevealResponse play(@PathVariable String id) {
		var scenario = scenarios.play(id);
		// Every played scenario feeds the training set, rated or not.
		feedback.recordOutcome(scenario);
		return RevealResponse.from(scenario);
	}

	@PostMapping("/{id}/feedback")
	public Map<String, Object> rate(@PathVariable String id, @Valid @RequestBody FeedbackRequest request) {
		feedback.recordRating(scenarios.get(id), request);
		return Map.of("recorded", true);
	}
}
