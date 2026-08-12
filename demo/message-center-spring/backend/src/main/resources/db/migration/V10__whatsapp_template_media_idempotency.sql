ALTER TABLE template_media_assets ADD COLUMN client_request_id varchar(255);
ALTER TABLE template_media_assets ADD COLUMN error_code varchar(100);
ALTER TABLE template_media_assets ADD COLUMN error_message text;
ALTER TABLE template_media_assets ADD COLUMN trace_id varchar(100);
ALTER TABLE template_media_assets ADD COLUMN started_at timestamptz;
ALTER TABLE template_media_assets ADD COLUMN updated_at timestamptz;

ALTER TABLE template_media_assets ALTER COLUMN provider_object_key DROP NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN provider_url DROP NOT NULL;

UPDATE template_media_assets
SET client_request_id = 'legacy:' || id::text,
    started_at = created_at,
    updated_at = COALESCE(attached_at, created_at);

ALTER TABLE template_media_assets ALTER COLUMN client_request_id SET NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN started_at SET NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN started_at SET DEFAULT now();
ALTER TABLE template_media_assets ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE template_media_assets ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE template_media_assets DROP CONSTRAINT ck_template_asset_status;
ALTER TABLE template_media_assets ADD CONSTRAINT ck_template_asset_status CHECK
    (asset_status IN ('PROCESSING','UPLOADED','FAILED','SUBMISSION_UNKNOWN',
                      'ATTACHED','ATTACHMENT_UNKNOWN','ORPHANED'));
ALTER TABLE template_media_assets ADD CONSTRAINT ck_template_media_request_id CHECK
    (char_length(client_request_id) BETWEEN 1 AND 255);
ALTER TABLE template_media_assets ADD CONSTRAINT ck_template_media_provider_result CHECK
    (asset_status IN ('PROCESSING','FAILED','SUBMISSION_UNKNOWN')
     OR (provider_object_key IS NOT NULL AND provider_url IS NOT NULL));

CREATE UNIQUE INDEX ux_template_media_assets_request
ON template_media_assets(channel_account_id, client_request_id);
