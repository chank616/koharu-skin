CREATE TABLE IF NOT EXISTS koharu_ygg_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_uid BIGINT NOT NULL,
    client_token TEXT NOT NULL,
    player_pid BIGINT,
    profile_name VARCHAR(50),
    credentials_hash CHAR(64) NOT NULL,
    issued_at BIGINT NOT NULL,
    valid_until BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX ygg_tokens_user (user_uid, issued_at),
    INDEX ygg_tokens_expiry (expires_at)
);
CREATE TABLE IF NOT EXISTS koharu_ygg_sessions (
    server_hash CHAR(64) PRIMARY KEY,
    token_hash CHAR(64) NOT NULL,
    client_ip VARCHAR(45) NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX ygg_sessions_expiry (expires_at)
);
CREATE TABLE IF NOT EXISTS koharu_union_nonces (
    nonce VARBINARY(128) PRIMARY KEY,
    expires_at BIGINT NOT NULL,
    INDEX union_nonces_expiry (expires_at)
);
CREATE TABLE IF NOT EXISTS koharu_player_renames (
    uuid VARCHAR(36) PRIMARY KEY,
    changed_at BIGINT NOT NULL
);
