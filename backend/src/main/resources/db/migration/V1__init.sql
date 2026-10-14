CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username citext NOT NULL UNIQUE,
    display_name text NOT NULL,
    password_hash text NOT NULL,
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked', 'pending_deletion')),
    bio text,
    avatar_object_key text,
    privacy_policy_accepted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    deleted_at timestamptz
);

CREATE TABLE devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name text NOT NULL,
    platform text NOT NULL CHECK (platform IN ('android', 'web', 'admin')),
    identity_public_key bytea,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz
);
CREATE INDEX devices_user_id_idx ON devices (user_id);

CREATE TABLE sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id uuid NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    refresh_token_hash text NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by uuid REFERENCES sessions (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    ip inet,
    user_agent text
);
CREATE INDEX sessions_active_user_idx ON sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash text NOT NULL UNIQUE,
    created_by uuid REFERENCES users (id),
    expires_at timestamptz NOT NULL,
    max_uses integer NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    use_count integer NOT NULL DEFAULT 0 CHECK (use_count >= 0 AND use_count <= max_uses),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    note text
);

CREATE TABLE invitation_uses (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invitation_id uuid NOT NULL REFERENCES invitations (id),
    user_id uuid NOT NULL REFERENCES users (id),
    used_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (invitation_id, user_id)
);

CREATE TABLE contacts (
    owner_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    contact_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    alias text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_user_id, contact_user_id),
    CHECK (owner_user_id <> contact_user_id)
);

CREATE TABLE blocks (
    blocker_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CHECK (blocker_user_id <> blocked_user_id)
);

CREATE TABLE conversations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type text NOT NULL CHECK (type IN ('direct', 'group')),
    title text,
    avatar_object_key text,
    created_by uuid REFERENCES users (id),
    direct_key text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (type = 'direct' AND direct_key IS NOT NULL AND title IS NULL)
        OR (type = 'group' AND direct_key IS NULL AND title IS NOT NULL)
    )
);

CREATE TABLE conversation_members (
    conversation_id uuid NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    left_at timestamptz,
    muted_until timestamptz,
    pinned_at timestamptz,
    archived_at timestamptz,
    last_read_seq bigint,
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX conversation_members_user_idx ON conversation_members (user_id) WHERE left_at IS NULL;

CREATE TABLE messages (
    seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_user_id uuid NOT NULL REFERENCES users (id),
    sender_device_id uuid NOT NULL REFERENCES devices (id),
    client_message_id uuid NOT NULL,
    reply_to_id uuid,
    kind text NOT NULL CHECK (kind IN ('text', 'attachment', 'voice', 'system')),
    encryption text NOT NULL CHECK (encryption IN ('none', 'signal_v1')),
    ciphertext bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    UNIQUE (sender_device_id, client_message_id)
);
CREATE INDEX messages_conversation_seq_idx ON messages (conversation_id, seq);
ALTER TABLE messages
    ADD CONSTRAINT messages_reply_fk FOREIGN KEY (reply_to_id) REFERENCES messages (id);

CREATE TABLE message_receipts (
    message_seq bigint NOT NULL REFERENCES messages (seq) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status text NOT NULL CHECK (status IN ('delivered', 'read')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_seq, user_id)
);

CREATE TABLE attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    uploader_user_id uuid NOT NULL REFERENCES users (id),
    conversation_id uuid REFERENCES conversations (id),
    object_key text NOT NULL UNIQUE,
    original_name text NOT NULL,
    mime_type text NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 bytea,
    client_encrypted boolean NOT NULL DEFAULT false,
    status text NOT NULL CHECK (status IN ('pending', 'ready', 'failed', 'deleted')),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE TABLE calls (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES conversations (id),
    caller_user_id uuid NOT NULL REFERENCES users (id),
    callee_user_id uuid NOT NULL REFERENCES users (id),
    kind text NOT NULL CHECK (kind IN ('audio', 'video')),
    state text NOT NULL CHECK (state IN ('ringing', 'active', 'ended', 'missed', 'rejected', 'failed')),
    started_at timestamptz NOT NULL DEFAULT now(),
    answered_at timestamptz,
    ended_at timestamptz,
    end_reason text,
    used_turn boolean
);
CREATE INDEX calls_conversation_idx ON calls (conversation_id, started_at DESC);

CREATE TABLE push_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id uuid NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider IN ('fcm', 'unifiedpush')),
    token text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    invalidated_at timestamptz,
    UNIQUE (provider, token)
);

CREATE TABLE admin_roles (
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('owner', 'admin', 'moderator')),
    granted_by uuid REFERENCES users (id),
    granted_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id uuid REFERENCES users (id),
    action text NOT NULL,
    target_type text,
    target_id text,
    ip inet,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_created_idx ON audit_events (created_at DESC);

CREATE TABLE app_settings (
    key text PRIMARY KEY,
    value jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid REFERENCES users (id)
);

CREATE TABLE outbox_events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;

INSERT INTO app_settings (key, value) VALUES
    ('file.max_bytes', '52428800'::jsonb),
    ('registration.invite_only', 'true'::jsonb),
    ('e2ee.enabled', 'false'::jsonb);
