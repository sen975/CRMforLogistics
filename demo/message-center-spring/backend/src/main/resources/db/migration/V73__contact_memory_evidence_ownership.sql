ALTER TABLE contact_memory_facts
    ADD CONSTRAINT uq_contact_memory_fact_identity
        UNIQUE (id, contact_id, owner_user_id);

ALTER TABLE contact_ai_labels
    ADD CONSTRAINT uq_contact_ai_label_identity
        UNIQUE (id, contact_id, owner_user_id);

ALTER TABLE contact_memory_observation_evidence
    ADD CONSTRAINT fk_contact_memory_observation_evidence_owner
        FOREIGN KEY (observation_id, contact_id, owner_user_id)
        REFERENCES contact_memory_observations (id, contact_id, owner_user_id)
        ON DELETE CASCADE;

ALTER TABLE contact_memory_fact_evidence
    ADD CONSTRAINT fk_contact_memory_fact_evidence_owner
        FOREIGN KEY (fact_id, contact_id, owner_user_id)
        REFERENCES contact_memory_facts (id, contact_id, owner_user_id)
        ON DELETE CASCADE;

ALTER TABLE contact_ai_label_evidence
    ADD CONSTRAINT fk_contact_ai_label_evidence_label_owner
        FOREIGN KEY (label_id, contact_id, owner_user_id)
        REFERENCES contact_ai_labels (id, contact_id, owner_user_id)
        ON DELETE CASCADE;

ALTER TABLE contact_ai_label_evidence
    ADD CONSTRAINT fk_contact_ai_label_evidence_fact_owner
        FOREIGN KEY (fact_id, contact_id, owner_user_id)
        REFERENCES contact_memory_facts (id, contact_id, owner_user_id)
        ON DELETE RESTRICT;
