package com.gusev.replaytrainer.feedback;

import jakarta.validation.constraints.NotNull;

public record FeedbackRequest(@NotNull Rating rating, String note) {
}
