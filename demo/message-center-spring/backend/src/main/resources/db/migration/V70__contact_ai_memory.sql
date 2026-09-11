CREATE TABLE contact_profile_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    version bigint NOT NULL,
    content varchar(200) NOT NULL,
    source_cursor varchar(255) NOT NULL,
    generation_batch_id uuid NOT NULL,
    model varchar(150) NOT NULL,
    input_message_count integer NOT NULL DEFAULT 0,
    evidence_count integer NOT NULL DEFAULT 0,
    is_current boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_profile_version UNIQUE (contact_id, owner_user_id, version),
    CONSTRAINT ck_contact_profile_version_number CHECK (version > 0),
    CONSTRAINT ck_contact_profile_content CHECK (length(btrim(content)) > 0),
    CONSTRAINT ck_contact_profile_input_count CHECK (input_message_count >= 0),
    CONSTRAINT ck_contact_profile_evidence_count CHECK (evidence_count >= 0)
);

CREATE UNIQUE INDEX ux_contact_profile_current
    ON contact_profile_versions (contact_id, owner_user_id)
    WHERE is_current;

CREATE INDEX ix_contact_profile_owner_contact
    ON contact_profile_versions (owner_user_id, contact_id, version DESC);

CREATE TABLE contact_memory_facts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    category varchar(40) NOT NULL,
    normalized_key varchar(200) NOT NULL,
    normalized_value varchar(500) NOT NULL,
    display_value varchar(500) NOT NULL,
    polarity varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    confidence numeric(4,3) NOT NULL,
    evidence_count integer NOT NULL DEFAULT 0,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    last_confirmed_at timestamptz NOT NULL,
    stale_at timestamptz,
    invalidated_at timestamptz,
    generation_batch_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_memory_fact_semantic
        UNIQUE (contact_id, owner_user_id, category, normalized_key, normalized_value, polarity),
    CONSTRAINT ck_contact_memory_fact_category CHECK (
        category IN (
            'IDENTITY',
            'PRODUCT_INTEREST',
            'NEED',
            'PERSONALITY_COMMUNICATION',
            'DECISION_FACTOR',
            'RISK',
            'RELATIONSHIP_STAGE',
            'OTHER_STABLE_TRAIT'
        )
    ),
    CONSTRAINT ck_contact_memory_fact_polarity CHECK (polarity IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    CONSTRAINT ck_contact_memory_fact_status CHECK (status IN ('ACTIVE', 'STALE', 'CONFLICTED', 'INACTIVE')),
    CONSTRAINT ck_contact_memory_fact_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_contact_memory_fact_evidence_count CHECK (evidence_count >= 0),
    CONSTRAINT ck_contact_memory_fact_time_order CHECK (last_seen_at >= first_seen_at)
);

CREATE INDEX ix_contact_memory_facts_owner_contact_status
    ON contact_memory_facts (owner_user_id, contact_id, status, last_confirmed_at DESC);

CREATE TABLE contact_memory_states (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    status varchar(20) NOT NULL DEFAULT 'CLEAN',
    last_inbound_at timestamptz,
    last_success_cursor varchar(255),
    current_profile_version_id uuid REFERENCES contact_profile_versions (id) ON DELETE SET NULL,
    retry_count integer NOT NULL DEFAULT 0,
    next_retry_at timestamptz,
    last_failure_code varchar(100),
    last_failure_message varchar(1000),
    lease_owner varchar(100),
    lease_acquired_at timestamptz,
    lease_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_memory_state_contact_owner UNIQUE (contact_id, owner_user_id),
    CONSTRAINT ck_contact_memory_state_status CHECK (
        status IN ('CLEAN', 'DIRTY', 'PROCESSING', 'RETRY_WAIT', 'FAILED')
    ),
    CONSTRAINT ck_contact_memory_state_retry_count CHECK (retry_count BETWEEN 0 AND 3),
    CONSTRAINT ck_contact_memory_state_lease_order CHECK (
        lease_expires_at IS NULL OR lease_acquired_at IS NULL OR lease_expires_at > lease_acquired_at
    )
);

CREATE INDEX ix_contact_memory_states_runnable
    ON contact_memory_states (status, next_retry_at, updated_at)
    WHERE status IN ('DIRTY', 'RETRY_WAIT');

CREATE INDEX ix_contact_memory_states_expired_processing
    ON contact_memory_states (lease_expires_at, updated_at)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_contact_memory_states_owner_contact
    ON contact_memory_states (owner_user_id, contact_id);

CREATE TABLE contact_memory_observations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    category varchar(40) NOT NULL,
    normalized_key varchar(200) NOT NULL,
    observed_value varchar(500) NOT NULL,
    polarity varchar(20) NOT NULL,
    confidence numeric(4,3) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'CANDIDATE',
    source_cursor varchar(255) NOT NULL,
    generation_batch_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    promoted_fact_id uuid REFERENCES contact_memory_facts (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_contact_memory_observation_category CHECK (
        category IN (
            'IDENTITY',
            'PRODUCT_INTEREST',
            'NEED',
            'PERSONALITY_COMMUNICATION',
            'DECISION_FACTOR',
            'RISK',
            'RELATIONSHIP_STAGE',
            'OTHER_STABLE_TRAIT'
        )
    ),
    CONSTRAINT ck_contact_memory_observation_polarity CHECK (polarity IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL')),
    CONSTRAINT ck_contact_memory_observation_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_contact_memory_observation_status CHECK (
        status IN ('CANDIDATE', 'PROMOTED', 'REJECTED', 'EXPIRED', 'MERGED')
    ),
    CONSTRAINT ck_contact_memory_observation_expiry CHECK (expires_at > observed_at)
);

CREATE INDEX ix_contact_memory_observations_owner_contact_status
    ON contact_memory_observations (owner_user_id, contact_id, status, expires_at);

CREATE INDEX ix_contact_memory_observations_batch
    ON contact_memory_observations (generation_batch_id, contact_id);

CREATE TABLE contact_memory_observation_evidence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    observation_id uuid NOT NULL REFERENCES contact_memory_observations (id) ON DELETE CASCADE,
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    evidence_type varchar(30) NOT NULL,
    evidence_id uuid NOT NULL,
    evidence_excerpt varchar(1000) NOT NULL DEFAULT '',
    generation_batch_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_memory_observation_evidence
        UNIQUE (observation_id, evidence_type, evidence_id),
    CONSTRAINT ck_contact_memory_observation_evidence_type CHECK (
        evidence_type IN ('MESSAGE', 'TOPIC', 'CALL_TRANSCRIPT')
    )
);

CREATE INDEX ix_contact_memory_observation_evidence_lookup
    ON contact_memory_observation_evidence (owner_user_id, contact_id, evidence_type, evidence_id);

CREATE TABLE contact_memory_fact_evidence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fact_id uuid NOT NULL REFERENCES contact_memory_facts (id) ON DELETE CASCADE,
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    evidence_type varchar(30) NOT NULL,
    evidence_id uuid NOT NULL,
    evidence_excerpt varchar(1000) NOT NULL DEFAULT '',
    generation_batch_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_memory_fact_evidence
        UNIQUE (fact_id, evidence_type, evidence_id),
    CONSTRAINT ck_contact_memory_fact_evidence_type CHECK (
        evidence_type IN ('MESSAGE', 'TOPIC', 'CALL_TRANSCRIPT')
    )
);

CREATE INDEX ix_contact_memory_fact_evidence_lookup
    ON contact_memory_fact_evidence (owner_user_id, contact_id, evidence_type, evidence_id);

CREATE TABLE contact_ai_labels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    category varchar(40) NOT NULL,
    normalized_name varchar(100) NOT NULL,
    display_name varchar(100) NOT NULL,
    color_token varchar(30) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    confidence numeric(4,3) NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    last_evidence_at timestamptz NOT NULL,
    generation_batch_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_ai_label_semantic
        UNIQUE (contact_id, owner_user_id, category, normalized_name),
    CONSTRAINT ck_contact_ai_label_category CHECK (
        category IN (
            'IDENTITY',
            'PRODUCT_INTEREST',
            'NEED',
            'PERSONALITY_COMMUNICATION',
            'DECISION_FACTOR',
            'RISK',
            'RELATIONSHIP_STAGE',
            'OTHER_STABLE_TRAIT'
        )
    ),
    CONSTRAINT ck_contact_ai_label_color CHECK (
        color_token IN ('blue', 'green', 'orange', 'purple', 'cyan', 'red', 'gray', 'brown')
    ),
    CONSTRAINT ck_contact_ai_label_status CHECK (status IN ('ACTIVE', 'STALE', 'INACTIVE')),
    CONSTRAINT ck_contact_ai_label_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_contact_ai_label_time_order CHECK (last_seen_at >= first_seen_at)
);

CREATE INDEX ix_contact_ai_labels_owner_contact_status
    ON contact_ai_labels (owner_user_id, contact_id, status, last_seen_at DESC);

CREATE TABLE contact_ai_label_evidence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    label_id uuid NOT NULL REFERENCES contact_ai_labels (id) ON DELETE CASCADE,
    fact_id uuid NOT NULL REFERENCES contact_memory_facts (id) ON DELETE RESTRICT,
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    evidence_type varchar(30) NOT NULL,
    evidence_id uuid NOT NULL,
    evidence_excerpt varchar(1000) NOT NULL DEFAULT '',
    generation_batch_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_contact_ai_label_evidence
        UNIQUE (label_id, fact_id, evidence_type, evidence_id),
    CONSTRAINT ck_contact_ai_label_evidence_type CHECK (
        evidence_type IN ('MESSAGE', 'TOPIC', 'CALL_TRANSCRIPT', 'LONG_TERM_FACT', 'PROFILE_VERSION')
    )
);

CREATE INDEX ix_contact_ai_label_evidence_lookup
    ON contact_ai_label_evidence (owner_user_id, contact_id, label_id, created_at DESC);

CREATE TABLE contact_memory_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id uuid NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    generation_batch_id uuid NOT NULL,
    input_cursor varchar(255),
    output_cursor varchar(255),
    status varchar(20) NOT NULL,
    failure_code varchar(100),
    failure_message varchar(2000),
    model varchar(150),
    duration_ms bigint,
    input_message_count integer NOT NULL DEFAULT 0,
    output_label_change_count integer NOT NULL DEFAULT 0,
    profile_changed boolean NOT NULL DEFAULT false,
    retry_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_contact_memory_attempt_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_contact_memory_attempt_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_contact_memory_attempt_input_count CHECK (input_message_count >= 0),
    CONSTRAINT ck_contact_memory_attempt_label_changes CHECK (output_label_change_count >= 0),
    CONSTRAINT ck_contact_memory_attempt_retry_count CHECK (retry_count BETWEEN 0 AND 3)
);

CREATE INDEX ix_contact_memory_attempts_owner_contact_time
    ON contact_memory_attempts (owner_user_id, contact_id, created_at DESC);

CREATE INDEX ix_contact_memory_attempts_batch
    ON contact_memory_attempts (generation_batch_id);
