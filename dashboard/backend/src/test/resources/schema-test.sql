-- H2-only schema fixture. Never run against MySQL (TEST_SQL_INIT=never).
CREATE TABLE IF NOT EXISTS posture_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 previous_state VARCHAR(20), state VARCHAR(20) NOT NULL,
 armed BOOLEAN NOT NULL,
 x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
 base INT NOT NULL, `change` INT NOT NULL, temperature INT NOT NULL,
 received_at DATETIME(6) NOT NULL
);
