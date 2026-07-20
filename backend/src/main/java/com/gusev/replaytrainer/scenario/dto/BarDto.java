package com.gusev.replaytrainer.scenario.dto;

import com.gusev.replaytrainer.market.Bar;

/** Compact bar for the chart: t is epoch seconds (lightweight-charts format). */
public record BarDto(long t, double o, double h, double l, double c, double v) {

	public static BarDto from(Bar bar) {
		return new BarDto(bar.time().getEpochSecond(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
	}
}
