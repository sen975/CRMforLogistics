-- An AI label no longer has to be anchored to a corroborated fact.
--
-- Facts are only promoted once a key carries two distinct evidence refs, which a
-- contact's first run can never supply: it starts with no facts and no prior
-- observations, and the model cites one message per observation. Requiring a
-- supporting fact therefore made every first run fail, and because the whole
-- mutation ran in one transaction the observations were rolled back with it, so
-- the next run started from the same empty state.
ALTER TABLE contact_ai_label_evidence
    ALTER COLUMN fact_id DROP NOT NULL;

-- Duplicate suppression has to survive the null fact_id. Under the default
-- NULLS DISTINCT every null compares as unique, so repeated runs would insert
-- the same evidence row again; NULLS NOT DISTINCT keeps the original guarantee.
ALTER TABLE contact_ai_label_evidence
    DROP CONSTRAINT uq_contact_ai_label_evidence;

ALTER TABLE contact_ai_label_evidence
    ADD CONSTRAINT uq_contact_ai_label_evidence
        UNIQUE NULLS NOT DISTINCT (label_id, fact_id, evidence_type, evidence_id);
