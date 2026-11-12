ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE password_reset_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
ALTER TABLE notification_email_outbox MODIFY notification_id BIGINT NULL;
ALTER TABLE notification_email_outbox ADD COLUMN sensitive BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notification_email_outbox ADD COLUMN expires_at DATETIME NULL;
