package dev.codewise.tracker.service;

import dev.codewise.tracker.config.TrackerProperties;
import dev.codewise.tracker.crypto.CodeCipher;
import dev.codewise.tracker.dto.ImportSubmissionRequest;
import dev.codewise.tracker.dto.ImportSubmissionResponse;
import dev.codewise.tracker.dto.MonthlyReportResponse;
import dev.codewise.tracker.dto.QuestionStatsResponse;
import dev.codewise.tracker.dto.SubmissionResponse;
import dev.codewise.tracker.repository.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class SubmissionService {

    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    private final SubmissionRepository repository;
    private final CodeCipher codeCipher;
    private final TrackerProperties properties;

    public SubmissionService(
            SubmissionRepository repository,
            CodeCipher codeCipher,
            TrackerProperties properties
    ) {
        this.repository = repository;
        this.codeCipher = codeCipher;
        this.properties = properties;
    }

    @Transactional
    public ImportSubmissionResponse importSubmission(ImportSubmissionRequest request) {
        boolean created = !repository.submissionExists(
                request.platform(),
                request.platformSubmissionId()
        );
        long questionId = repository.upsertQuestion(request);
        long submissionId = repository.upsertSubmission(
                properties.userId(),
                questionId,
                request,
                codeCipher.encrypt(request.code())
        );
        return new ImportSubmissionResponse(submissionId, created);
    }

    @Transactional(readOnly = true)
    public List<QuestionStatsResponse> getQuestionStats() {
        return repository.findQuestionStats(properties.userId());
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> getQuestionSubmissions(String problemKey) {
        return repository.findSubmissions(properties.userId(), problemKey)
                .stream()
                .map(submission -> new SubmissionResponse(
                        submission.id(),
                        submission.platformSubmissionId(),
                        submission.language(),
                        codeCipher.decrypt(submission.encryptedCode()),
                        submission.status(),
                        submission.runtime(),
                        submission.memory(),
                        submission.passedTests(),
                        submission.totalTests(),
                        submission.submittedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public MonthlyReportResponse getMonthlyReport(int year, int month) {
        YearMonth reportMonth = YearMonth.of(year, month);
        LocalDateTime startUtc = LocalDateTime.ofInstant(
                reportMonth.atDay(1).atStartOfDay(REPORT_ZONE).toInstant(),
                ZoneOffset.UTC
        );
        LocalDateTime endUtc = LocalDateTime.ofInstant(
                reportMonth.plusMonths(1).atDay(1).atStartOfDay(REPORT_ZONE).toInstant(),
                ZoneOffset.UTC
        );
        List<SubmissionRepository.MonthlySubmission> submissions = repository.findMonthlySubmissions(
                properties.userId(),
                startUtc,
                endUtc
        );

        Set<String> attemptedQuestions = new LinkedHashSet<>();
        Set<String> solvedQuestions = new LinkedHashSet<>();
        Set<LocalDate> activeDays = new LinkedHashSet<>();
        Map<LocalDate, long[]> dailyCounts = new LinkedHashMap<>();
        Map<String, Long> statusDistribution = new LinkedHashMap<>();
        Map<String, Long> languageDistribution = new LinkedHashMap<>();
        Map<String, MonthlyReportResponse.SolvedQuestion> acceptedQuestions = new LinkedHashMap<>();
        long acceptedSubmissions = 0;

        for (SubmissionRepository.MonthlySubmission submission : submissions) {
            LocalDateTime localSubmittedAt = submission.submittedAt()
                    .toInstant(ZoneOffset.UTC)
                    .atZone(REPORT_ZONE)
                    .toLocalDateTime();
            LocalDate submittedDate = localSubmittedAt.toLocalDate();
            boolean accepted = "Accepted".equals(submission.status());

            attemptedQuestions.add(submission.problemKey());
            activeDays.add(submittedDate);
            statusDistribution.merge(submission.status(), 1L, Long::sum);
            languageDistribution.merge(submission.language(), 1L, Long::sum);
            long[] daily = dailyCounts.computeIfAbsent(submittedDate, ignored -> new long[2]);
            daily[0]++;

            if (accepted) {
                acceptedSubmissions++;
                solvedQuestions.add(submission.problemKey());
                daily[1]++;
                acceptedQuestions.putIfAbsent(
                        submission.problemKey(),
                        new MonthlyReportResponse.SolvedQuestion(
                                submission.problemKey(),
                                submission.title(),
                                submission.url(),
                                submission.difficulty(),
                                localSubmittedAt
                        )
                );
            }
        }

        List<MonthlyReportResponse.DailyActivity> dailyActivity = new ArrayList<>();
        for (int day = 1; day <= reportMonth.lengthOfMonth(); day++) {
            LocalDate date = reportMonth.atDay(day);
            long[] counts = dailyCounts.getOrDefault(date, new long[2]);
            dailyActivity.add(new MonthlyReportResponse.DailyActivity(date, counts[0], counts[1]));
        }

        return new MonthlyReportResponse(
                year,
                month,
                submissions.size(),
                acceptedSubmissions,
                attemptedQuestions.size(),
                solvedQuestions.size(),
                activeDays.size(),
                dailyActivity,
                statusDistribution,
                languageDistribution,
                new ArrayList<>(acceptedQuestions.values())
        );
    }
}
