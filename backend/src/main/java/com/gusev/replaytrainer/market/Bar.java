package com.gusev.replaytrainer.market;

import java.time.Instant;

public record Bar(Instant time, double open, double high, double low, double close, double volume) {
}
