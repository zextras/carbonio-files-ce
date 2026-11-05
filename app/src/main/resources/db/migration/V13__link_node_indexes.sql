-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Deleting a node cascades to link and collaboration_link by node_id: without an index each deleted node scans both
-- tables (permanent delete, trash purge, ownership transfer).

BEGIN;

CREATE INDEX IF NOT EXISTS link_node_id ON link (node_id);
CREATE INDEX IF NOT EXISTS collaboration_link_node_id ON collaboration_link (node_id);

COMMIT;
