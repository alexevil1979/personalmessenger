ALTER TABLE attachments
    ADD COLUMN message_id uuid REFERENCES messages (id);

CREATE UNIQUE INDEX attachments_message_idx ON attachments (message_id) WHERE message_id IS NOT NULL;
