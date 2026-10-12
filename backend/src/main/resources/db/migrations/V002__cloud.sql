CREATE SEQUENCE ct_cloud_revision_seq;
CREATE TABLE ct_cloud_entities (
 family_id varchar(100) NOT NULL REFERENCES ct_families(id) ON DELETE CASCADE,
 entity_type varchar(16) NOT NULL CHECK (entity_type IN ('profile','card','scene','room','report')),
 entity_id varchar(100) NOT NULL,
 revision bigint NOT NULL UNIQUE CHECK (revision > 0),
 profile_id varchar(100),
 tombstone boolean NOT NULL,
 payload jsonb,
 PRIMARY KEY (family_id,entity_type,entity_id),
 CHECK ((tombstone AND payload IS NULL) OR (NOT tombstone AND payload IS NOT NULL))
);
CREATE INDEX ct_cloud_family_cursor ON ct_cloud_entities(family_id,revision);
CREATE TABLE ct_cloud_operations (
 family_id varchar(100) NOT NULL REFERENCES ct_families(id) ON DELETE CASCADE,
 operation_id varchar(100) NOT NULL,
 actor_id varchar(100) NOT NULL,
 request_sha256 char(64) NOT NULL,
 revision bigint NOT NULL,
 PRIMARY KEY (family_id,operation_id)
);
