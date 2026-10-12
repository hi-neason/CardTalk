CREATE TABLE ct_support_tickets (
    seq bigserial UNIQUE NOT NULL,
    id varchar(36) PRIMARY KEY,
    owner_hash char(64) NOT NULL,
    request_hash char(64) NOT NULL,
    payload_hash char(64) NOT NULL,
    kind varchar(32) NOT NULL CHECK(kind IN ('support','privacy_deletion')),
    message text NOT NULL,
    contact varchar(200) NOT NULL,
    family_id varchar(100),
    status varchar(32) NOT NULL CHECK(status IN ('submitted','in_progress','resolved','rejected','withdrawn')),
    reply text NOT NULL DEFAULT '',
    deletion_evidence text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    retain_until timestamptz,
    UNIQUE(owner_hash,request_hash)
);
CREATE INDEX ct_support_owner_page ON ct_support_tickets(owner_hash,seq);
CREATE TABLE ct_support_audit (
    seq bigserial PRIMARY KEY,
    ticket_id varchar(36) NOT NULL REFERENCES ct_support_tickets(id) ON DELETE CASCADE,
    actor_hash char(64) NOT NULL,
    action varchar(32) NOT NULL,
    request_hash char(64),
    payload_hash char(64),
    reply text NOT NULL,
    deletion_evidence text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE(ticket_id,actor_hash,request_hash)
);
CREATE TABLE ct_support_maintenance_audit (
    seq bigserial PRIMARY KEY,
    actor_hash char(64) NOT NULL,
    removed_count integer NOT NULL,
    created_at timestamptz NOT NULL
);
