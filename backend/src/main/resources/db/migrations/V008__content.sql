CREATE TABLE IF NOT EXISTS ct_content_versions(id varchar(60) PRIMARY KEY,payload jsonb NOT NULL,created_by text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE IF NOT EXISTS ct_content_release(singleton integer PRIMARY KEY CHECK(singleton=1),revision bigint NOT NULL,version_id varchar(60) REFERENCES ct_content_versions(id));
INSERT INTO ct_content_release VALUES(1,0,NULL) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS ct_content_audit(revision bigint PRIMARY KEY,version_id varchar(60) NOT NULL REFERENCES ct_content_versions(id),actor_id text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp());
