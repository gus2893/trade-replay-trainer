package com.gusev.replaytrainer.scenario;

import com.gusev.replaytrainer.sim.TradeDirection;

/**
 * What the scenario actually was — detected by looking at both sides of the
 * cut. Held server-side and revealed only after playback: the teaching moment.
 * direction SKIP means the right play was to pass.
 */
public record SetupInfo(SetupType type, TradeDirection direction, double moveAtr, String description) {

	public enum SetupType {
		LIQUIDITY_SWEEP,
		BREAKOUT_CONTINUATION,
		FAILED_BREAKOUT,
		VWAP_REVERSION,
		TREND_PULLBACK,
		MOMENTUM_RUN,
		CHOP,
		MIXED
	}

	public boolean isRealSetup() {
		return direction != TradeDirection.SKIP && type != SetupType.MIXED;
	}

	public boolean isChop() {
		return type == SetupType.CHOP;
	}
}
