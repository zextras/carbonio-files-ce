-- SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

ALTER TABLE link ALTER COLUMN public_id TYPE VARCHAR(50);

UPDATE db_info SET version = 6;

COMMIT;
