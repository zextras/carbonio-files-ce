-- SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

CREATE TABLE IF NOT EXISTS invitation_link (
    id CHARACTER(36) NOT NULL PRIMARY KEY,
    node_id CHARACTER(36) NOT NULL REFERENCES node ON DELETE CASCADE,
    invitation_id CHARACTER(8) NOT NULL,
    created_at bigint NOT NULL,
    permissions BIGINT
);

CREATE INDEX IF NOT EXISTS invitation_link_table_index_id ON invitation_link (id);

CREATE INDEX IF NOT EXISTS invitation_link_table_index_invitation_id
    ON invitation_link (invitation_id);

UPDATE db_info SET version = 2;

COMMIT;