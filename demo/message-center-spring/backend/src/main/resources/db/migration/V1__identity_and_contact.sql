CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username varchar(100) NOT NULL,
    username_normalized varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active',
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT ck_users_status CHECK (status IN ('active', 'locked', 'disabled'))
);

CREATE UNIQUE INDEX ux_users_username_normalized
    ON users (username_normalized) WHERE deleted_at IS NULL;

CREATE TABLE roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(50) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO roles (code, display_name) VALUES
    ('agent', 'Agent'),
    ('supervisor', 'Supervisor'),
    ('admin', 'Administrator'),
    ('owner', 'Owner')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id),
    role_id uuid NOT NULL REFERENCES roles (id),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    assigned_by uuid REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id),
    token_hash bytea NOT NULL UNIQUE,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    ip_address inet,
    user_agent varchar(500),
    CONSTRAINT ck_user_sessions_expiry CHECK (expires_at > issued_at)
);

CREATE INDEX ix_user_sessions_user_active
    ON user_sessions (user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE teams (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active',
    supervisor_id uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_teams_status CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE team_members (
    team_id uuid NOT NULL REFERENCES teams (id),
    user_id uuid NOT NULL REFERENCES users (id),
    responsibility varchar(50) NOT NULL DEFAULT 'member',
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);

CREATE TABLE companies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(200) NOT NULL,
    type_code varchar(50),
    country varchar(100),
    city varchar(100),
    website varchar(500),
    owner_id uuid REFERENCES users (id) ON DELETE SET NULL,
    remark text,
    status varchar(20) NOT NULL DEFAULT 'active',
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_companies_status CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE company_tags (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    color varchar(30),
    status varchar(20) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_company_tags_status CHECK (status IN ('active', 'disabled'))
);

CREATE UNIQUE INDEX ux_company_tags_name ON company_tags (lower(name));

CREATE TABLE company_taggings (
    company_id uuid NOT NULL REFERENCES companies (id),
    tag_id uuid NOT NULL REFERENCES company_tags (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, tag_id)
);

CREATE TABLE contacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name varchar(100) NOT NULL,
    role_title varchar(100),
    remark text,
    status varchar(20) NOT NULL DEFAULT 'active',
    merged_to_id uuid REFERENCES contacts (id),
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_contacts_status CHECK (status IN ('active', 'disabled', 'merged')),
    CONSTRAINT ck_contacts_merge_target
        CHECK ((status = 'merged' AND merged_to_id IS NOT NULL) OR status <> 'merged'),
    CONSTRAINT ck_contacts_not_self_merged CHECK (merged_to_id IS NULL OR merged_to_id <> id)
);

CREATE TABLE contact_tags (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    color varchar(30),
    status varchar(20) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_contact_tags_status CHECK (status IN ('active', 'disabled'))
);

CREATE UNIQUE INDEX ux_contact_tags_name ON contact_tags (lower(name));

CREATE TABLE contact_taggings (
    contact_id uuid NOT NULL REFERENCES contacts (id),
    tag_id uuid NOT NULL REFERENCES contact_tags (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (contact_id, tag_id)
);

CREATE TABLE company_contacts (
    company_id uuid NOT NULL REFERENCES companies (id),
    contact_id uuid NOT NULL REFERENCES contacts (id),
    relation_type varchar(50) NOT NULL DEFAULT 'contact',
    is_primary boolean NOT NULL DEFAULT false,
    remark text,
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, contact_id)
);

CREATE TABLE contact_identities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid REFERENCES contacts (id),
    channel_type varchar(30) NOT NULL,
    identity_scope varchar(255) NOT NULL DEFAULT 'global',
    identity_value varchar(255) NOT NULL,
    normalized_value varchar(255) NOT NULL,
    display_name varchar(100),
    is_primary boolean NOT NULL DEFAULT false,
    verify_status varchar(20) NOT NULL DEFAULT 'unverified',
    source varchar(30) NOT NULL DEFAULT 'manual',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_contact_identities_channel CHECK (channel_type IN ('email', 'whatsapp', 'wecom', 'phone')),
    CONSTRAINT ck_contact_identities_verify CHECK (verify_status IN ('unverified', 'verified')),
    CONSTRAINT ck_contact_identities_source CHECK (source IN ('manual', 'synced', 'imported'))
);

CREATE UNIQUE INDEX ux_contact_identity_normalized
    ON contact_identities (channel_type, identity_scope, normalized_value)
    WHERE deleted_at IS NULL;

CREATE TABLE phone_notes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id),
    company_id uuid REFERENCES companies (id),
    phone_identity_id uuid REFERENCES contact_identities (id),
    occurred_at timestamptz NOT NULL,
    summary text NOT NULL,
    next_step text,
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_phone_notes_contact_time ON phone_notes (contact_id, occurred_at DESC, id);
