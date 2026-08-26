ALTER TABLE wecom_parties
    DROP CONSTRAINT IF EXISTS ck_wecom_party_type;

ALTER TABLE wecom_parties
    ADD CONSTRAINT ck_wecom_party_type
    CHECK (party_type IN ('EMPLOYEE', 'EXTERNAL_CONTACT', 'ROBOT', 'GROUP'));
