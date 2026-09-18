-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

CREATE TABLE carbonio_config (
  scope_type  varchar(16)  NOT NULL,
  scope_id    varchar(256) NOT NULL,
  config_key  varchar(256) NOT NULL,
  value       text,
  PRIMARY KEY (scope_type, scope_id, config_key)
);
CREATE INDEX idx_carbonio_config_lookup ON carbonio_config (config_key, scope_type, scope_id);

COMMIT;
