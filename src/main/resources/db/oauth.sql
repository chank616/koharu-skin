CREATE TABLE IF NOT EXISTS koharu_oauth_authorizations (
    id VARCHAR(100) PRIMARY KEY,
    client_id BIGINT NOT NULL,
    user_id BIGINT,
    fingerprint CHAR(64),
    state_hash CHAR(64) UNIQUE,
    code_hash CHAR(64) UNIQUE,
    access_hash CHAR(64) UNIQUE,
    refresh_hash CHAR(64) UNIQUE,
    payload LONGTEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS koharu_oauth_consents (
    client_id VARCHAR(100) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    authorities TEXT NOT NULL,
    PRIMARY KEY(client_id,principal_name)
);
CREATE TABLE IF NOT EXISTS code_id_to_uuid (
    id INT AUTO_INCREMENT PRIMARY KEY,
    code_id VARCHAR(255) NOT NULL UNIQUE,
    uuid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
