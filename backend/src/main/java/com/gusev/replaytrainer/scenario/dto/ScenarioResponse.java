package com.gusev.replaytrainer.scenario.dto;

import java.util.List;

import com.gusev.replaytrainer.scenario.Scenario;

public record ScenarioResponse(
		String id,
		String displaySymbol,
		String assetClass,
		int barMinutes,
		boolean masked,
		double lastClose,
		List<BarDto> bars) {

	public static ScenarioResponse from(Scenario s) {
		return new ScenarioResponse(
				s.id,
				s.masked ? "?????" : s.symbol,
				s.assetClass.name(),
				s.barMinutes,
				s.masked,
				s.lastVisibleClose(),
				s.contextBars.stream().map(BarDto::from).toList());
	}
}
