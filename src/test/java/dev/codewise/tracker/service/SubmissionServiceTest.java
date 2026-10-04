package dev.codewise.tracker.service;

import dev.codewise.tracker.config.TrackerProperties;
import dev.codewise.tracker.crypto.CodeCipher;
import dev.codewise.tracker.repository.SubmissionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmissionServiceTest {

    @Test
    void shouldBuildMonthlyReportInShanghaiTime() {
        SubmissionRepository repository = mock(SubmissionRepository.class);
        CodeCipher codeCipher = mock(CodeCipher.class);
        TrackerProperties properties = new TrackerProperties("local-key", 1, "unused");
        SubmissionService service = new SubmissionService(repository, codeCipher, properties);
        when(repository.findMonthlySubmissions(
                1,
                LocalDateTime.of(2026, 6, 30, 16, 0),
                LocalDateTime.of(2026, 7, 31, 16, 0)
        )).thenReturn(List.of(
                submission("739126850", "Accepted", LocalDateTime.of(2026, 7, 30, 15, 44)),
                submission("739127175", "Accepted", LocalDateTime.of(2026, 7, 30, 15, 46))
        ));

        var report = service.getMonthlyReport(2026, 7);

        assertEquals(2, report.totalSubmissions());
        assertEquals(2, report.acceptedSubmissions());
        assertEquals(1, report.attemptedQuestions());
        assertEquals(1, report.solvedQuestions());
        assertEquals(1, report.activeDays());
        assertEquals(2, report.dailyActivity().get(29).submissions());
        assertEquals(1, report.acceptedQuestions().size());
        assertEquals("Easy", report.acceptedQuestions().getFirst().difficulty());
        assertTrue(report.statusDistribution().containsKey("Accepted"));
    }

    private SubmissionRepository.MonthlySubmission submission(
            String ignoredSubmissionId,
            String status,
            LocalDateTime submittedAt
    ) {
        return new SubmissionRepository.MonthlySubmission(
                "split-a-string-in-balanced-strings",
                "1221. 分割平衡字符串",
                "https://leetcode.cn/problems/split-a-string-in-balanced-strings/",
                "Easy",
                status,
                "golang",
                submittedAt
        );
    }
}
