package dev.codewise.tracker.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigration {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void addQuestionDifficultyColumn() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'tracker_question'
                  AND COLUMN_NAME = 'difficulty'
                """,
                Integer.class
        );
        if (columnCount != null && columnCount == 0) {
            jdbcTemplate.execute(
                    "ALTER TABLE tracker_question ADD COLUMN difficulty VARCHAR(16) NULL AFTER url"
            );
        }
    }
}
