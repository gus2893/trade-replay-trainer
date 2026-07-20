package com.gusev.replaytrainer.scenario.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/** The client-simulated managed exit result (partials, break-even, trailing). */
public record ManagedRequest(@NotNull Double r, List<String> actions) {
}
