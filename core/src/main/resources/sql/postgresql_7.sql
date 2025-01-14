-- SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

BEGIN;

/* Replace all nodetypes from OTHER to MESSAGE for all nodes with extensions related to messages */
UPDATE node
SET node_type = 'MESSAGE'
WHERE node_type = 'OTHER'
AND split_part(name, '.', -1) IN ('u8msg', 'u8dsn', 'u8mdn', 'u8hdr', 'eml', 'mail', 'art', 'msg');

/* Replace mimetypes on revision table from application/octet-stream to application/vnd.ms-outlook for files with .msg extension */
UPDATE revision
SET mime_type = 'application/vnd.ms-outlook'
FROM node
WHERE revision.node_id = node.node_id
AND revision.mime_type = 'application/octet-stream'
AND split_part(node.name, '.', -1) = 'msg';

UPDATE db_info SET version = 7;

COMMIT;
