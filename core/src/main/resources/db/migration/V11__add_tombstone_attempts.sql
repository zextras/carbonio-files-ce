-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

ALTER TABLE tombstone ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

COMMIT;
