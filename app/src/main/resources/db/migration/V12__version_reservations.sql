-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- upload-version and cloneVersion no longer lock the node row while the blob is written: the next version number is
-- reserved with one atomic UPDATE of node.last_version before any I/O, so two saves never write the same storage key
-- (the version number is part of it) and a failed save never makes the next one reuse its key.
-- last_version starts from the highest number already used (current_version, or a higher revision left by an older
-- bug), so no number backed by a blob is handed out again; a node blocked by a blob left at current_version + 1 by a
-- failed save of an older release fails one more save (409) and then moves on. last_reserved_at only grows per node:
-- revision dates follow the reservation order.
ALTER TABLE node ADD COLUMN last_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE node ADD COLUMN last_reserved_at BIGINT NOT NULL DEFAULT 0;

UPDATE node
SET last_version = GREATEST(COALESCE(node.current_version, 0), revisions.max_version),
    last_reserved_at = revisions.max_timestamp
FROM (SELECT revision.node_id, MAX(revision.version) AS max_version, MAX(revision.timestamp) AS max_timestamp
      FROM revision
      GROUP BY revision.node_id) AS revisions
WHERE node.node_id = revisions.node_id;

-- One row per reserved version number until its save is confirmed. A row older than the purge expiry means the save
-- failed: no revision references its blob, so the purge job deletes the blob (if any) and then the row.
CREATE TABLE IF NOT EXISTS version_reservation (
    node_id CHARACTER(36) NOT NULL,
    version INTEGER NOT NULL,
    owner_id VARCHAR(256) NOT NULL,
    reserved_at BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (node_id, version)
);

CREATE INDEX IF NOT EXISTS version_reservation_state_reserved_at ON version_reservation (state, reserved_at);
