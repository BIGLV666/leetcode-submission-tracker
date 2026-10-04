package dev.codewise.tracker.dto;

import java.time.LocalDateTime;

public record SubmissionResponse(
        long id,
        String platformSubmissionId,
        String language,
        String code,
        String status,
        String runtime,
        String memory,
        Integer passedTests,
        Integer totalTests,
        LocalDateTime submittedAt
) {
}
