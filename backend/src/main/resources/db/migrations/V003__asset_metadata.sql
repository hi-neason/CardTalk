CREATE TABLE ct_asset_metadata (
    id uuid PRIMARY KEY,
    family_id varchar(100) NOT NULL REFERENCES ct_families(id),
    profile_id varchar(100) NOT NULL CHECK (profile_id ~ '^[A-Za-z0-9_-]{1,100}$'),
    media_type varchar(20) NOT NULL CHECK (media_type IN ('image/png','image/jpeg','image/webp')),
    bytes bigint NOT NULL CHECK (bytes > 0),
    width integer NOT NULL CHECK (width > 0),
    height integer NOT NULL CHECK (height > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL
);
CREATE INDEX ct_asset_metadata_family_profile ON ct_asset_metadata(family_id,profile_id);
CREATE FUNCTION ct_asset_metadata_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Published asset metadata is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER ct_asset_metadata_no_update BEFORE UPDATE OR DELETE ON ct_asset_metadata
FOR EACH ROW EXECUTE FUNCTION ct_asset_metadata_immutable();
