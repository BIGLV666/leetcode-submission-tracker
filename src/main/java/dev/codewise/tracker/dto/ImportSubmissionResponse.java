package dev.codewise.tracker.dto;

public record ImportSubmissionResponse(
        long submissionId,
        boolean created
) {
}
