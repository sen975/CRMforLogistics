ALTER TABLE users
    ADD COLUMN IF NOT EXISTS avatar_object_key varchar(300),
    ADD COLUMN IF NOT EXISTS avatar_mime_type varchar(50),
    ADD COLUMN IF NOT EXISTS avatar_size_bytes bigint,
    ADD COLUMN IF NOT EXISTS avatar_updated_at timestamptz;

ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_avatar_metadata;
ALTER TABLE users ADD CONSTRAINT ck_users_avatar_metadata CHECK (
    (avatar_object_key IS NULL AND avatar_mime_type IS NULL
        AND avatar_size_bytes IS NULL AND avatar_updated_at IS NULL)
    OR
    (avatar_object_key LIKE 'user-avatars/%'
        AND avatar_mime_type IN ('image/jpeg', 'image/png')
        AND avatar_size_bytes BETWEEN 1 AND 2097152
        AND avatar_updated_at IS NOT NULL)
);
