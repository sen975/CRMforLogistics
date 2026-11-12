-- Drop baseline tables never wired to code: no entities, mappers, or tests in
-- message-center-spring reference them, and the legacy message-center-demo that
-- once used them is retired. All six are empty, so no data is lost.
DROP TABLE IF EXISTS company_taggings;
DROP TABLE IF EXISTS company_tags;
DROP TABLE IF EXISTS data_import_errors;
DROP TABLE IF EXISTS data_import_batches;
DROP TABLE IF EXISTS conversation_read_states;
DROP TABLE IF EXISTS message_participants;
