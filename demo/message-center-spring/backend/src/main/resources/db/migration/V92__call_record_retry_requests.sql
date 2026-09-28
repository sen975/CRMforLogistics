CREATE TABLE call_record_retry_requests (
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    call_record_id uuid NOT NULL REFERENCES call_records(id) ON DELETE CASCADE,
    client_request_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, call_record_id, client_request_id)
);
