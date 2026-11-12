ALTER TABLE message_templates
    ADD COLUMN IF NOT EXISTS template_domain varchar(32) NOT NULL DEFAULT 'ENTERPRISE_API';

UPDATE message_templates
SET template_domain = 'ENTERPRISE_API'
WHERE template_domain IS NULL OR btrim(template_domain) = '';

ALTER TABLE message_templates
    ADD CONSTRAINT ck_message_template_domain
        CHECK (template_domain IN ('ENTERPRISE_API', 'EMPLOYEE_BUSINESS_APP'));

DROP INDEX IF EXISTS ux_message_templates_shared_identity;

CREATE UNIQUE INDEX ux_message_templates_shared_identity
    ON message_templates(provider_scope_id, provider_template_id, language_code)
    WHERE template_domain = 'ENTERPRISE_API'
      AND provider_scope_id IS NOT NULL
      AND deleted_at IS NULL;

CREATE UNIQUE INDEX ux_message_templates_private_identity
    ON message_templates(channel_account_id, provider_template_id, language_code)
    WHERE template_domain = 'EMPLOYEE_BUSINESS_APP'
      AND channel_account_id IS NOT NULL
      AND deleted_at IS NULL;

CREATE INDEX ix_message_templates_private_account
    ON message_templates(channel_account_id, deleted_at, status, language_code, updated_at DESC)
    WHERE template_domain = 'EMPLOYEE_BUSINESS_APP';
