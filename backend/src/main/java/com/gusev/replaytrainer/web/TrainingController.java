package com.gusev.replaytrainer.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gusev.replaytrainer.feedback.FeedbackService;

@RestController
@RequestMapping("/api/training")
public class TrainingController {

	private final FeedbackService feedback;

	public TrainingController(FeedbackService feedback) {
		this.feedback = feedback;
	}

	@GetMapping("/stats")
	public Map<String, Object> stats() {
		return feedback.stats();
	}
}
