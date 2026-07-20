package com.gusev.replaytrainer.scenario.dto;

import java.util.List;

import com.gusev.replaytrainer.scenario.Scenario;
import com.gusev.replaytrainer.scenario.SetupInfo;

public record RevealResponse(
		String symbol,
		String assetClass,
		long cutTime,
		List<BarDto> futureBars,
		TradeReport user,
		TradeReport model,
		SetupInfo setup) {

	public static RevealResponse from(Scenario s) {
		return new RevealResponse(
				s.symbol,
				s.assetClass.name(),
				s.contextBars.get(s.contextBars.size() - 1).time().getEpochSecond(),
				s.futureBars.stream().map(BarDto::from).toList(),
				TradeReport.of(s.userTrade(), s.userOutcome()),
				TradeReport.of(s.modelPlan, s.modelOutcome()),
				s.setup);
	}
}
