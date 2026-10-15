DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'messages'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%encryption%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE messages DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE messages
    ADD CONSTRAINT messages_encryption_check
    CHECK (encryption IN ('none', 'signal_v1', 'x25519_v1'));
