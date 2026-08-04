CREATE INDEX ix_company_tags_name_trgm
    ON company_tags USING gin (name gin_trgm_ops);

CREATE INDEX ix_contact_tags_name_trgm
    ON contact_tags USING gin (name gin_trgm_ops);
