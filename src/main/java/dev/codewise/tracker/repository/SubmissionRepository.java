package dev.codewise.tracker.repository;

import dev.codewise.tracker.dto.ImportSubmissionRequest;
import dev.codewise.tracker.dto.QuestionStatsResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class SubmissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long upsertQuestion(ImportSubmissionRequest request) {
        jdbcTemplate.update(
                """
                INSERT INTO tracker_question(platform, problem_key, title, url, difficulty)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title),
                    url = VALUES(url),
                    difficulty = COALESCE(NULLIF(VALUES(difficulty), ''), difficulty)
                """,
                request.platform(),
                request.problemKey(),
                request.title(),
                request.url(),
                request.difficulty()
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tracker_question WHERE platform = ? AND problem_key = ?",
                Long.class,
                request.platform(),
                request.problemKey()
        );
    }

    public boolean submissionExists(String platform, String platformSubmissionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tracker_submission WHERE platform = ? AND platform_submission_id = ?",
                Integer.class,
                platform,
                platformSubmissionId
        );
        return count != null && count > 0;
    }

    public long upsertSubmission(
            long userId,
            long questionId,
            ImportSubmissionRequest request,
            String encryptedCode
    ) {
        LocalDateTime submittedAt = LocalDateTime.ofInstant(request.submittedAt(), ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO tracker_submission(
                    user_id, question_id, platform, platform_submission_id, language,
                    code_ciphertext, status, runtime, memory, passed_tests, total_tests, submitted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    language = VALUES(language), code_ciphertext = VALUES(code_ciphertext),
                    status = VALUES(status), runtime = VALUES(runtime), memory = VALUES(memory),
                    passed_tests = VALUES(passed_tests), total_tests = VALUES(total_tests),
                    submitted_at = VALUES(submitted_at)
                """,
                userId,
                questionId,
                request.platform(),
                request.platformSubmissionId(),
                request.language(),
                encryptedCode,
                request.status(),
                request.runtime(),
                request.memory(),
                request.passedTests(),
                request.totalTests(),
                Timestamp.valueOf(submittedAt)
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tracker_submission WHERE platform = ? AND platform_submission_id = ?",
                Long.class,
                request.platform(),
                request.platformSubmissionId()
        );
    }

    public List<QuestionStatsResponse> findQuestionStats(long userId) {
        return jdbcTemplate.query(
                """
                SELECT q.platform, q.problem_key, q.title, q.url, q.difficulty,
                       COUNT(s.id) AS submission_count,
                       SUM(CASE WHEN s.status = 'Accepted' THEN 1 ELSE 0 END) AS accepted_count,
                       MIN(CASE WHEN s.status = 'Accepted' THEN s.submitted_at END) AS first_accepted_at,
                       MAX(s.submitted_at) AS last_submitted_at
                FROM tracker_submission s
                JOIN tracker_question q ON q.id = s.question_id
                WHERE s.user_id = ?
                GROUP BY q.id, q.platform, q.problem_key, q.title, q.url, q.difficulty
                ORDER BY last_submitted_at DESC
                """,
                (resultSet, rowNumber) -> {
                    long acceptedCount = resultSet.getLong("accepted_count");
                    Timestamp firstAcceptedAt = resultSet.getTimestamp("first_accepted_at");
                    return new QuestionStatsResponse(
                            resultSet.getString("platform"),
                            resultSet.getString("problem_key"),
                            resultSet.getString("title"),
                            resultSet.getString("url"),
                            resultSet.getString("difficulty"),
                            resultSet.getLong("submission_count"),
                            acceptedCount,
                            acceptedCount > 0,
                            firstAcceptedAt == null ? null : firstAcceptedAt.toLocalDateTime(),
                            resultSet.getTimestamp("last_submitted_at").toLocalDateTime()
                    );
                },
                userId
        );
    }

    public List<StoredSubmission> findSubmissions(long userId, String problemKey) {
        return jdbcTemplate.query(
                """
                SELECT s.id, s.platform_submission_id, s.language, s.code_ciphertext,
                       s.status, s.runtime, s.memory, s.passed_tests, s.total_tests, s.submitted_at
                FROM tracker_submission s
                JOIN tracker_question q ON q.id = s.question_id
                WHERE s.user_id = ? AND q.problem_key = ?
                ORDER BY s.submitted_at DESC
                """,
                (resultSet, rowNumber) -> new StoredSubmission(
                        resultSet.getLong("id"),
                        resultSet.getString("platform_submission_id"),
                        resultSet.getString("language"),
                        resultSet.getString("code_ciphertext"),
                        resultSet.getString("status"),
                        resultSet.getString("runtime"),
                        resultSet.getString("memory"),
                        (Integer) resultSet.getObject("passed_tests"),
                        (Integer) resultSet.getObject("total_tests"),
                        resultSet.getTimestamp("submitted_at").toLocalDateTime()
                ),
                userId,
                problemKey
        );
    }

    public List<MonthlySubmission> findMonthlySubmissions(
            long userId,
            LocalDateTime startInclusive,
            LocalDateTime endExclusive
    ) {
        return jdbcTemplate.query(
                """
                SELECT q.problem_key, q.title, q.url, q.difficulty,
                       s.status, s.language, s.submitted_at
                FROM tracker_submission s
                JOIN tracker_question q ON q.id = s.question_id
                WHERE s.user_id = ? AND s.submitted_at >= ? AND s.submitted_at < ?
                ORDER BY s.submitted_at ASC
                """,
                (resultSet, rowNumber) -> new MonthlySubmission(
                        resultSet.getString("problem_key"),
                        resultSet.getString("title"),
                        resultSet.getString("url"),
                        resultSet.getString("difficulty"),
                        resultSet.getString("status"),
                        resultSet.getString("language"),
                        resultSet.getTimestamp("submitted_at").toLocalDateTime()
                ),
                userId,
                Timestamp.valueOf(startInclusive),
                Timestamp.valueOf(endExclusive)
        );
    }

    public record StoredSubmission(
            long id,
            String platformSubmissionId,
            String language,
            String encryptedCode,
            String status,
            String runtime,
            String memory,
            Integer passedTests,
            Integer totalTests,
            LocalDateTime submittedAt
    ) {
    }

    public record MonthlySubmission(
            String problemKey,
            String title,
            String url,
            String difficulty,
            String status,
            String language,
            LocalDateTime submittedAt
    ) {
    }
}
