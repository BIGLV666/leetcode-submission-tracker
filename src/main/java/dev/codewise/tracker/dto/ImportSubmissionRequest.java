package dev.codewise.tracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record ImportSubmissionRequest(
        @NotBlank String platform,
        @NotBlank String platformSubmissionId,
        @NotBlank String problemKey,
        @NotBlank String title,
        @NotBlank String url,
        String difficulty,
        @NotBlank String language,
        @NotBlank String code,
        @NotBlank String status,
        String runtime,
        String memory,
        @PositiveOrZero Integer passedTests,
        @PositiveOrZero Integer totalTests,
        @NotNull Instant submittedAt
) {
}
