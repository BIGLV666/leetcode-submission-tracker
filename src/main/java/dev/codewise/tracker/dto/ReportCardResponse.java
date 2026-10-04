package dev.codewise.tracker.dto;

public record ReportCardResponse(
        String fileName,
        int year,
        int month
) {
}
