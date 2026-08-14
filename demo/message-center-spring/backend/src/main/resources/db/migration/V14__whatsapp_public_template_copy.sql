ALTER TABLE template_operations
    DROP CONSTRAINT IF EXISTS ck_template_operation_type;

ALTER TABLE template_operations
    ADD CONSTRAINT ck_template_operation_type CHECK
        (operation_type IN ('CREATE','MODIFY','SET_SEND_PERMISSION','DELETE','RECONCILE','COPY'));
