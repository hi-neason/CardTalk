CREATE TABLE ct_report_quota (
    identity_hash char(64) NOT NULL,
    quota_day date NOT NULL,
    nonce_hash char(64) NOT NULL,
    facts_hash char(64) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('pending','completed','failed')),
    attempt varchar(36) NOT NULL,
    expires_at bigint NOT NULL,
    PRIMARY KEY(identity_hash,quota_day,nonce_hash)
);
