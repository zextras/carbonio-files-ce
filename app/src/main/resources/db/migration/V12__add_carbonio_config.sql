-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

CREATE TABLE carbonio_account_config (
  account_id varchar(256) NOT NULL,
  config_key varchar(256) NOT NULL,
  value      text,
  PRIMARY KEY (account_id, config_key)
);

CREATE TABLE carbonio_cos_config (
  cos_id     varchar(256) NOT NULL,
  config_key varchar(256) NOT NULL,
  value      text,
  PRIMARY KEY (cos_id, config_key)
);

CREATE TABLE carbonio_domain_config (
  domain_id  varchar(256) NOT NULL,
  config_key varchar(256) NOT NULL,
  value      text,
  PRIMARY KEY (domain_id, config_key)
);

CREATE TABLE carbonio_global_config (
  config_key varchar(256) NOT NULL,
  value      text,
  PRIMARY KEY (config_key)
);

COMMIT;
