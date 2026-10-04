CREATE TABLE IF NOT EXISTS tracker_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    platform VARCHAR(32) NOT NULL,
    problem_key VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL,
    url VARCHAR(500) NOT NULL,
    difficulty VARCHAR(16),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_tracker_question_platform_key (platform, problem_key)
);

CREATE TABLE IF NOT EXISTS tracker_submission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    platform VARCHAR(32) NOT NULL,
    platform_submission_id VARCHAR(128) NOT NULL,
    language VARCHAR(32) NOT NULL,
    code_ciphertext MEDIUMTEXT NOT NULL,
    status VARCHAR(64) NOT NULL,
    runtime VARCHAR(32),
    memory VARCHAR(32),
    passed_tests INT,
    total_tests INT,
    submitted_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_tracker_submission_question
        FOREIGN KEY (question_id) REFERENCES tracker_question(id),
    UNIQUE KEY uk_tracker_submission_platform_id (platform, platform_submission_id),
    KEY idx_tracker_submission_user_question (user_id, question_id),
    KEY idx_tracker_submission_user_status (user_id, status),
    KEY idx_tracker_submission_submitted_at (submitted_at)
);
