CREATE TABLE ct_trial_sessions (
    client_id varchar(128) PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE
);
CREATE TABLE ct_trial_daily (
    client_id varchar(128) NOT NULL REFERENCES ct_trial_sessions(client_id),
    quota_day date NOT NULL,
    count integer NOT NULL CHECK (count >= 0),
    PRIMARY KEY(client_id,quota_day)
);
CREATE TABLE ct_trial_requests (
    client_id varchar(128) NOT NULL REFERENCES ct_trial_sessions(client_id),
    nonce_hash char(64) NOT NULL,
    facts_hash char(64) NOT NULL,
    quota_day date NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('pending','completed','failed')),
    attempt varchar(36) NOT NULL,
    expires_at bigint NOT NULL,
    PRIMARY KEY(client_id,nonce_hash)
);
CREATE INDEX ct_trial_pending ON ct_trial_requests(client_id,quota_day) WHERE state='pending';
