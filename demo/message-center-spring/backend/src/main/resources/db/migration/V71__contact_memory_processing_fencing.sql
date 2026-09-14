ALTER TABLE contact_memory_states
    ADD COLUMN lease_token uuid;

ALTER TABLE contact_memory_observations
    ADD CONSTRAINT uq_contact_memory_observation_identity
        UNIQUE (id, contact_id, owner_user_id);
