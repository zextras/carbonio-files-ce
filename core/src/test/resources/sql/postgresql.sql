-- SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- postgresql_1.sql adapted for hsqldb

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

CREATE TABLE IF NOT EXISTS share (
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    rights SMALLINT,
    timestamp BIGINT NOT NULL,
    target_uuid VARCHAR(256) NOT NULL,
    expire_date BIGINT,
    direct BOOLEAN DEFAULT TRUE NOT NULL,

    PRIMARY KEY(node_id, target_uuid)
);

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

INSERT INTO node
VALUES (NULL, 'LOCAL_ROOT', NULL, 'ROOT', 'ROOT', 0, '', 2, 0,
        0, NULL, NULL, NULL, '', 0);

INSERT INTO node
VALUES (NULL, 'TRASH_ROOT', NULL, 'TRASH', 'ROOT', 0, '', 2, 0,
        0, NULL, NULL, NULL, '', 0);

INSERT INTO db_info
VALUES (1);

CREATE TABLE IF NOT EXISTS collaboration_link (
    id UUID NOT NULL PRIMARY KEY,
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    invitation_id CHARACTER(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    permissions SMALLINT
);

-- postgresql_2.sql adapted for hsqldb

ALTER TABLE share ADD COLUMN IF NOT EXISTS created_via_link BOOLEAN DEFAULT FALSE NOT NULL;

UPDATE db_info SET version = 2;
