-- SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

CREATE TABLE IF NOT EXISTS collaboration_link (
    id UUID NOT NULL PRIMARY KEY,
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    collaboration_id CHARACTER(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    permissions SMALLINT
);

CREATE INDEX IF NOT EXISTS collaboration_link_table_index_id ON collaboration_link (id);

CREATE INDEX IF NOT EXISTS collaboration_link_table_index_collaboration_id
    ON collaboration_link (collaboration_id);

UPDATE db_info SET version = 2;

COMMIT;