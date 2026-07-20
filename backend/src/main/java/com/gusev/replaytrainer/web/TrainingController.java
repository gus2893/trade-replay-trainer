package com.gusev.replaytrainer.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gusev.replaytrainer.feedback.FeedbackService;
import com.gusev.replaytrainer.learn.LearnedFilter;

@RestController
@RequestMapping("/api/training")
public class TrainingController {

	private final FeedbackService feedback;
	private final LearnedFilter filter;

	public TrainingController(FeedbackService feedback, LearnedFilter filter) {
		this.feedback = feedback;
		this.filter = filter;
	}

	@GetMapping("/stats")
	public Map<String, Object> stats() {
		Map<String, Object> stats = new java.util.LinkedHashMap<>(feedback.stats());
		stats.put("learnedFilter", filter.info());
		return stats;
	}
}
