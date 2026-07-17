-- SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

-- Snapshot tables (duplicate data but useful for saving historic data)
CREATE TABLE IF NOT EXISTS snapshot_node (
    snapshot_node_id CHARACTER(36) PRIMARY KEY,
    snapshot_timestamp BIGINT NOT NULL,
    owner_id VARCHAR(256),
    node_id CHARACTER(36) NOT NULL,
    name VARCHAR(1024) NOT NULL,
    node_type VARCHAR(50) NOT NULL,
    creation_timestamp BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS snapshot_user (
    snapshot_user_id VARCHAR(256) PRIMARY KEY,
    snapshot_timestamp BIGINT NOT NULL,
    user_id VARCHAR(256) NOT NULL,
    full_name VARCHAR(1024) NOT NULL,
    email VARCHAR(1024) NOT NULL
);

CREATE TABLE IF NOT EXISTS notification (
    notification_id VARCHAR(256) PRIMARY KEY,
    created_at BIGINT NOT NULL,
    notification_type VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS new_share_notification (
    notification_id VARCHAR(256) PRIMARY KEY REFERENCES notification(notification_id) ON DELETE CASCADE,
    node_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_node(snapshot_node_id),
    triggering_user_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_user(snapshot_user_id)
);

CREATE TABLE IF NOT EXISTS added_node_notification (
    notification_id VARCHAR(256) PRIMARY KEY REFERENCES notification(notification_id) ON DELETE CASCADE,
    added_node_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_node(snapshot_node_id),
    destination_folder_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_node(snapshot_node_id),
    triggering_user_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_user(snapshot_user_id),
    added_node_type VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS removed_node_notification (
    notification_id VARCHAR(256) PRIMARY KEY REFERENCES notification(notification_id) ON DELETE CASCADE,
    removed_node_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_node(snapshot_node_id),
    origin_folder_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_node(snapshot_node_id),
    triggering_user_snapshot_id VARCHAR(256) NOT NULL REFERENCES snapshot_user(snapshot_user_id),
    removed_node_type VARCHAR(50) NOT NULL
);

-- Since we don't have a table of users we create one for the sake of the notification. If user is not in here,
-- we can assume last_seen is 0 (never seen notifications).
-- Similarly, unread is 0 if user is not in here: when a notification gets created we also update the unread counter,
-- adding the user to the table. There is no case where an user with a notification is not present in this table.
-- The unread value is useful to display how many news the user has not yet seen, since they will not necessarily
-- all be in the same page since they are paginated (and requesting N pages until lastseen is reached is not efficient).
CREATE TABLE IF NOT EXISTS user_notifications_info (
    user_id VARCHAR(256) NOT NULL PRIMARY KEY,
    last_seen BIGINT NOT NULL,
    unread INT NOT NULL
);

-- This maps notifications to which users should see them.
-- N:N, since a user can see multiple notifications and a notification can be seen by multiple users.
CREATE TABLE IF NOT EXISTS user_notification_interest (
    interest_id VARCHAR(256) PRIMARY KEY,
    user_id VARCHAR(256) NOT NULL,
    notification_id VARCHAR(256) NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_notifications_info(user_id),
    FOREIGN KEY (notification_id) REFERENCES notification(notification_id)
);

-- Let's index the hell out of it
CREATE INDEX IF NOT EXISTS idx_user_notification_interest_user_created_at
  ON user_notification_interest (user_id, created_at DESC, notification_id DESC);

CREATE INDEX IF NOT EXISTS idx_new_share_node_snapshot
  ON new_share_notification (node_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_new_share_triggering_user
  ON new_share_notification (triggering_user_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_added_node_snapshot
  ON added_node_notification (added_node_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_added_destination_folder
  ON added_node_notification (destination_folder_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_added_triggering_user
  ON added_node_notification (triggering_user_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_removed_node_snapshot
  ON removed_node_notification (removed_node_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_removed_origin_folder
  ON removed_node_notification (origin_folder_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_removed_triggering_user
  ON removed_node_notification (triggering_user_snapshot_id);

CREATE INDEX IF NOT EXISTS idx_snapshot_node_node_id_timestamp
  ON snapshot_node (node_id, snapshot_timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_snapshot_user_user_id_timestamp
  ON snapshot_user (user_id, snapshot_timestamp DESC);

UPDATE db_info SET version = 8;

COMMIT;
