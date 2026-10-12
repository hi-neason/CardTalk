CREATE TABLE ct_family_accounts (
    id varchar(100) PRIMARY KEY,
    username varchar(40) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_]{3,40}$'),
    salt varchar(128) NOT NULL,
    password_hash varchar(128) NOT NULL
);
CREATE TABLE ct_family_sessions (
    token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    account_id varchar(100) NOT NULL REFERENCES ct_family_accounts(id) ON DELETE CASCADE,
    expires_at bigint NOT NULL CHECK (expires_at > 0)
);
CREATE INDEX ct_family_sessions_account ON ct_family_sessions(account_id);
CREATE TABLE ct_families (
    id varchar(100) PRIMARY KEY,
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0)
);
CREATE TABLE ct_family_members (
    family_id varchar(100) NOT NULL REFERENCES ct_families(id) ON DELETE CASCADE,
    account_id varchar(100) NOT NULL REFERENCES ct_family_accounts(id) ON DELETE CASCADE,
    role varchar(10) NOT NULL CHECK (role IN ('owner','admin','member','helper')),
    PRIMARY KEY (family_id, account_id)
);
CREATE INDEX ct_family_members_account ON ct_family_members(account_id);
CREATE TABLE ct_family_profile_access (
    family_id varchar(100) NOT NULL,
    account_id varchar(100) NOT NULL,
    profile_id varchar(100) NOT NULL CHECK (profile_id ~ '^[A-Za-z0-9_-]{1,100}$'),
    PRIMARY KEY (family_id, account_id, profile_id),
    FOREIGN KEY (family_id, account_id) REFERENCES ct_family_members(family_id, account_id) ON DELETE CASCADE
);
CREATE TABLE ct_family_invitations (
    token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    family_id varchar(100) NOT NULL REFERENCES ct_families(id) ON DELETE CASCADE,
    issuer_id varchar(100) NOT NULL REFERENCES ct_family_accounts(id) ON DELETE CASCADE,
    role varchar(10) NOT NULL CHECK (role IN ('admin','member','helper')),
    expires_at bigint NOT NULL CHECK (expires_at > 0)
);
CREATE INDEX ct_family_invitations_family ON ct_family_invitations(family_id);
CREATE TABLE ct_family_invitation_access (
    token_hash char(64) NOT NULL REFERENCES ct_family_invitations(token_hash) ON DELETE CASCADE,
    profile_id varchar(100) NOT NULL CHECK (profile_id ~ '^[A-Za-z0-9_-]{1,100}$'),
    PRIMARY KEY (token_hash, profile_id)
);
