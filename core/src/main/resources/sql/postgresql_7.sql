-- SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

/* Replace all nodetypes from OTHER to MESSAGE for all nodes with mimetype message/<something> */
/* Replace mimetypes on revision table from application/octet-stream to application/vnd.ms-outlook for files with .msg extension
and replace their nodetypes with MESSAGE */

COMMIT;
