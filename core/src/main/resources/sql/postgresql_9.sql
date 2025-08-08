-- SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

DROP INDEX IF EXISTS node_table_index_folder_id;
CREATE INDEX node_table_index_folder_id
ON node(folder_id, node_id, node_type, size, owner_id);

CREATE INDEX IF NOT EXISTS idx_node_ancestors
ON node(ancestor_ids);

CREATE INDEX IF NOT EXISTS idx_share_rights
ON share(node_id, target_uuid, rights);

UPDATE db_info SET version = 9;

COMMIT;
