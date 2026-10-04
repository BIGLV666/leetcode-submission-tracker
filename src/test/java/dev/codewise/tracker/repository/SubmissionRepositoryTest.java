package dev.codewise.tracker.repository;

import dev.codewise.tracker.dto.ImportSubmissionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JdbcTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tracker;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SubmissionRepository.class)
class SubmissionRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubmissionRepository repository;

    @BeforeEach
    void createSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS tracker_submission");
        jdbcTemplate.execute("DROP TABLE IF EXISTS tracker_question");
        jdbcTemplate.execute("""
                CREATE TABLE tracker_question (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    platform VARCHAR(32) NOT NULL,
                    problem_key VARCHAR(128) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    url VARCHAR(500) NOT NULL,
                    difficulty VARCHAR(16),
                    UNIQUE(platform, problem_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE tracker_submission (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    question_id BIGINT NOT NULL,
                    platform VARCHAR(32) NOT NULL,
                    platform_submission_id VARCHAR(128) NOT NULL,
                    language VARCHAR(32) NOT NULL,
                    code_ciphertext CLOB NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    runtime VARCHAR(32),
                    memory VARCHAR(32),
                    passed_tests INT,
                    total_tests INT,
                    submitted_at TIMESTAMP NOT NULL,
                    UNIQUE(platform, platform_submission_id)
                )
                """);
    }

    @Test
    void shouldUpsertSubmissionAndGroupStatsByQuestion() {
        ImportSubmissionRequest accepted = request("1001", "Accepted", "1 ms");
        long questionId = repository.upsertQuestion(accepted);
        repository.upsertSubmission(7, questionId, accepted, "encrypted-code-1");

        ImportSubmissionRequest updated = request("1001", "Wrong Answer", "2 ms");
        repository.upsertSubmission(7, questionId, updated, "encrypted-code-2");
        ImportSubmissionRequest second = request("1002", "Accepted", "3 ms");
        repository.upsertSubmission(7, questionId, second, "encrypted-code-3");

        var stats = repository.findQuestionStats(7);
        var submissions = repository.findSubmissions(7, "two-sum");

        assertEquals(1, stats.size());
        assertEquals(2, stats.getFirst().submissionCount());
        assertEquals(1, stats.getFirst().acceptedCount());
        assertEquals("Easy", stats.getFirst().difficulty());
        assertTrue(stats.getFirst().solved());
        assertEquals(2, submissions.size());
        assertTrue(submissions.stream()
                .anyMatch(submission -> "encrypted-code-2".equals(submission.encryptedCode())));
        assertTrue(submissions.stream()
                .noneMatch(submission -> "encrypted-code-1".equals(submission.encryptedCode())));
    }

    private ImportSubmissionRequest request(String submissionId, String status, String runtime) {
        return new ImportSubmissionRequest(
                "leetcode-cn",
                submissionId,
                "two-sum",
                "两数之和",
                "https://leetcode.cn/problems/two-sum/",
                "Easy",
                "java",
                "class Solution {}",
                status,
                runtime,
                "40 MB",
                10,
                10,
                Instant.parse("2026-07-30T10:00:00Z")
        );
    }
}
