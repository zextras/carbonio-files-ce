-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- upload-version and cloneVersion no longer lock the node row while the blob is written: the next version number is
-- reserved with one atomic UPDATE of node.last_version before any I/O, so two saves never write the same storage key
-- (the version number is part of it) and a failed save never makes the next one reuse its key.
-- A reservation hands out GREATEST(last_version, current_version) + 1, so last_version is set here only on the nodes
-- that already used a number above current_version (a revision or a tombstone left by an older bug): those numbers
-- may still be backed by a blob and are never handed out again. Every other node keeps 0 and starts from
-- current_version. A node blocked by a blob left at current_version + 1 by a failed save of an older release (no row
-- references it) fails one more save, because the storage refuses the existing key, and then moves on.
-- last_reserved_at is a per-node clock that only grows, used to date the versions in reservation order; it starts at
-- 0 and follows the instance clock from the first reservation.
ALTER TABLE node ADD COLUMN last_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE node ADD COLUMN last_reserved_at BIGINT NOT NULL DEFAULT 0;

UPDATE node
SET last_version = used.max_version
FROM (SELECT node_id, MAX(version) AS max_version
      FROM (SELECT node_id, version FROM revision
            UNION ALL
            SELECT node_id, version FROM tombstone) AS versions
      GROUP BY node_id) AS used
WHERE node.node_id = used.node_id
  AND used.max_version > COALESCE(node.current_version, 0);

-- One row per reserved version number until its save is confirmed. A row older than the purge expiry means the save
-- failed: no revision references its blob, so the purge job deletes the blob (if any) and then the row (state PENDING,
-- then PURGING once the purge claims it). Overwrites of an existing version take no reservation.
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

-- An overwrite (the Docs autosave) rewrites the current version in place: the storages accept two writes of one key at
-- the same time and may keep either or mix them, so Files lets one overwrite of a node start at a time. The lease is
-- taken and released in short transactions (no transaction or connection held while the blob is written) and expires
-- after 30 s, so a crashed or slow overwrite never blocks the next one longer than that; the write itself is never cut.
-- registered_at dates the rewritten version; a row whose lease expired long ago is removed by the purge job.
CREATE TABLE IF NOT EXISTS overwrite_lease (
    node_id CHARACTER(36) NOT NULL PRIMARY KEY,
    version INTEGER NOT NULL,
    token CHARACTER(36) NOT NULL,
    registered_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS overwrite_lease_expires_at ON overwrite_lease (expires_at);
