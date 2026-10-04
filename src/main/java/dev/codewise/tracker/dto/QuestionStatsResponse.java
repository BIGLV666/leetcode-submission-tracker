package dev.codewise.tracker.dto;

import java.time.LocalDateTime;

public record QuestionStatsResponse(
        String platform,
        String problemKey,
        String title,
        String url,
        String difficulty,
        long submissionCount,
        long acceptedCount,
        boolean solved,
        LocalDateTime firstAcceptedAt,
        LocalDateTime lastSubmittedAt
) {
}
