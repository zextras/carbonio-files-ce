-- SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

ALTER TABLE link ADD COLUMN access_code VARCHAR(255) DEFAULT NULL;

UPDATE db_info SET version = 5;

COMMIT;
