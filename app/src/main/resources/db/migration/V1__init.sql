-- SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;
CREATE TABLE IF NOT EXISTS db_info (
    version INTEGER NOT NULL PRIMARY KEY
);

CREATE TABLE IF NOT EXISTS node (
    owner_id VARCHAR(256),
    node_id CHARACTER(36) NOT NULL PRIMARY KEY,
    folder_id CHARACTER(36),
    name VARCHAR(1024) NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    node_category SMALLINT NOT NULL,
    description VARCHAR(4096) NOT NULL,
    index_status SMALLINT,
    creation_timestamp BIGINT NOT NULL,
    updated_timestamp BIGINT NOT NULL,
    creator_id CHARACTER(36),
    editor_id CHARACTER(36),
    current_version INTEGER default NULL,
    ancestor_ids VARCHAR(4096),
    size BIGINT
);

CREATE TABLE IF NOT EXISTS activity (
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    action_type SMALLINT NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    timestamp BIGINT NOT NULL,
    info VARCHAR(65536)
);

CREATE INDEX IF NOT EXISTS index_activity ON activity (node_id, timestamp);

CREATE TABLE IF NOT EXISTS custom (
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    user_id VARCHAR(256) NOT NULL,
    star boolean DEFAULT FALSE NOT NULL,
    color SMALLINT,
    extra VARCHAR(65536) NOT NULL,

    PRIMARY KEY(node_id, user_id)
);

CREATE TABLE IF NOT EXISTS link (
    id CHARACTER(36) NOT NULL PRIMARY KEY,
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    public_id CHARACTER(8) NOT NULL,
    created_at bigint NOT NULL,
    expire_at BIGINT,
    description VARCHAR(300)
);

CREATE INDEX IF NOT EXISTS node_table_index_creation_timestamp ON node (creation_timestamp);

CREATE INDEX IF NOT EXISTS node_table_index_folder_id ON node (folder_id);

CREATE INDEX IF NOT EXISTS node_table_index_name ON node (name);

CREATE INDEX IF NOT EXISTS node_table_index_node_type ON node (node_type);

CREATE INDEX IF NOT EXISTS node_table_index_owner_id ON node (owner_id);

CREATE INDEX IF NOT EXISTS node_table_index_updated_timestamp ON node (updated_timestamp);

CREATE TABLE IF NOT EXISTS revision (
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    version INTEGER NOT NULL,
    mime_type VARCHAR(256) NOT NULL,
    size BIGINT NOT NULL,
    digest VARCHAR(128) NOT NULL,
    editor_id VARCHAR(256) NOT NULL,
    timestamp BIGINT NOT NULL,
    is_autosave BOOLEAN DEFAULT FALSE,
    keep_forever BOOLEAN DEFAULT FALSE NOT NULL,
    cloned_from_version INTEGER DEFAULT NULL,

    PRIMARY KEY(node_id, version)
);

CREATE INDEX IF NOT EXISTS revision_table_index_mime_type ON revision (mime_type);

CREATE INDEX IF NOT EXISTS revision_table_index_version ON revision (version);

CREATE TABLE IF NOT EXISTS share (
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    rights SMALLINT,
    timestamp BIGINT NOT NULL,
    target_uuid VARCHAR(256) NOT NULL,
    expire_date BIGINT,
    direct BOOLEAN DEFAULT TRUE NOT NULL,

    PRIMARY KEY(node_id, target_uuid)
);

CREATE INDEX IF NOT EXISTS share_table_index_target_uuid ON share (target_uuid);

CREATE TABLE IF NOT EXISTS tombstone (
    node_id CHARACTER(36) NOT NULL,
    owner_id VARCHAR(256),
    timestamp BIGINT NOT NULL,
    version INTEGER NOT NULL,

    PRIMARY KEY(node_id, version)
);

CREATE TABLE IF NOT EXISTS trashed (
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    parent_id CHARACTER(36) NOT NULL,

    PRIMARY KEY(node_id)
);

CREATE INDEX IF NOT EXISTS trashed_table_index_parent_id ON trashed (parent_id);

INSERT INTO node
VALUES (NULL, 'LOCAL_ROOT', NULL, 'ROOT', 'ROOT', 0, '', 2, EXTRACT(EPOCH FROM NOW()),
        EXTRACT(EPOCH FROM NOW()), NULL,
        NULL, NULL, '', 0)
ON CONFLICT DO NOTHING;

INSERT INTO node
VALUES (NULL, 'TRASH_ROOT', NULL, 'TRASH', 'ROOT', 0, '', 2, EXTRACT(EPOCH FROM NOW()),
        EXTRACT(EPOCH FROM NOW()), NULL,
        NULL, NULL, '', 0)
ON CONFLICT DO NOTHING;

INSERT INTO db_info
VALUES (1)
ON CONFLICT DO NOTHING;

COMMIT;