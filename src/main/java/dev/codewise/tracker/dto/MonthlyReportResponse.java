package dev.codewise.tracker.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record MonthlyReportResponse(
        int year,
        int month,
        long totalSubmissions,
        long acceptedSubmissions,
        long attemptedQuestions,
        long solvedQuestions,
        long activeDays,
        List<DailyActivity> dailyActivity,
        Map<String, Long> statusDistribution,
        Map<String, Long> languageDistribution,
        List<SolvedQuestion> acceptedQuestions
) {
    public record DailyActivity(
            LocalDate date,
            long submissions,
            long accepted
    ) {
    }

    public record SolvedQuestion(
            String problemKey,
            String title,
            String url,
            String difficulty,
            LocalDateTime acceptedAt
    ) {
    }
}
