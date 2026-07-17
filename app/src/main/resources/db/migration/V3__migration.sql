-- SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

ALTER TABLE link ALTER COLUMN public_id TYPE VARCHAR(32);

UPDATE db_info SET version = 3;

COMMIT;
