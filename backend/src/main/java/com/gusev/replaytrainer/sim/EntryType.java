package com.gusev.replaytrainer.sim;

/** MARKET fills at the next bar's open; LIMIT rests at a better price and may never fill. */
public enum EntryType {
	MARKET, LIMIT
}
