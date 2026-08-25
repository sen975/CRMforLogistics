ALTER TABLE template_operations
    DROP CONSTRAINT IF EXISTS ck_template_operation_type;

UPDATE template_operations
SET operation_type = 'RETIRED',
    next_reconcile_at = NULL,
    lease_owner = NULL,
    lease_until = NULL,
    error_code = COALESCE(error_code, 'OPERATION_RETIRED'),
    error_message = COALESCE(error_message, '旧公共模板复制路径已退役')
WHERE operation_type = 'COPY';

ALTER TABLE template_operations
    ADD CONSTRAINT ck_template_operation_type CHECK
        (operation_type IN ('CREATE','MODIFY','SET_SEND_PERMISSION','DELETE','RECONCILE','RETIRED'));
