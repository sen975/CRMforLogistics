ALTER TABLE whatsapp_authorization_attempts
    ADD COLUMN country_code varchar(4),
    ADD COLUMN phone_number varchar(32);

ALTER TABLE whatsapp_authorization_attempts
    ADD CONSTRAINT ck_whatsapp_authorization_attempt_phone
        CHECK ((country_code IS NULL AND phone_number IS NULL)
            OR (btrim(country_code) <> '' AND btrim(phone_number) <> ''));
