-- SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

create table DB_INFO
(
    VERSION INTEGER not null
);

create table NODE
(
    OWNER_ID           VARCHAR(256),
    NODE_ID            CHARACTER(36) not null primary key,
    FOLDER_ID          CHARACTER(36),
    NAME               VARCHAR(1024) not null,
    NODE_TYPE          VARCHAR(50)   not null,
    NODE_CATEGORY      SMALLINT      not null,
    DESCRIPTION        VARCHAR(4096) not null,
    INDEX_STATUS       SMALLINT,
    CREATION_TIMESTAMP BIGINT        not null,
    UPDATED_TIMESTAMP  BIGINT        not null,
    CREATOR_ID         CHARACTER(36),
    EDITOR_ID          CHARACTER(36),
    CURRENT_VERSION    INTEGER default NULL,
    ANCESTOR_IDS       VARCHAR(4096),
    SIZE               BIGINT
);

create table ACTIVITY
(
    NODE_ID     CHARACTER(36) not null
        references NODE
            on delete cascade,
    ACTION_TYPE SMALLINT      not null,
    USER_ID     VARCHAR(256)  not null,
    TIMESTAMP   BIGINT        not null,
    INFO        VARCHAR(65536)
);

create index INDEX_ACTIVITY
    on ACTIVITY (NODE_ID, TIMESTAMP);

create table CUSTOM
(
    NODE_ID CHARACTER(36)         not null
        references NODE
            on delete cascade,
    USER_ID VARCHAR(256)          not null,
    STAR    BOOLEAN default FALSE not null,
    COLOR   SMALLINT,
    EXTRA   VARCHAR(65536)        not null,
    primary key (NODE_ID, USER_ID)
);

create table LINK
(
    ID          CHARACTER(36) NOT NULL PRIMARY KEY,
    NODE_ID     CHARACTER(36) NOT NULL
        references NODE
            on delete cascade,
    PUBLIC_ID   CHARACTER(8)  NOT NULL,
    CREATED_AT  BIGINT        not null,
    EXPIRE_AT   BIGINT,
    DESCRIPTION VARCHAR(300)
);

create index NODE_TABLE_INDEX_CREATION_TIMESTAMP
    on NODE (CREATION_TIMESTAMP);

create index NODE_TABLE_INDEX_FOLDER_ID
    on NODE (FOLDER_ID);

create index NODE_TABLE_INDEX_NAME
    on NODE (NAME);

create index NODE_TABLE_INDEX_NODE_TYPE
    on NODE (NODE_TYPE);

create index NODE_TABLE_INDEX_OWNER_ID
    on NODE (OWNER_ID);

create index NODE_TABLE_INDEX_UPDATED_TIMESTAMP
    on NODE (UPDATED_TIMESTAMP);

create table REVISION
(
    NODE_ID             CHARACTER(36)         not null
        references NODE
            on delete cascade,
    VERSION             INTEGER               not null,
    MIME_TYPE           VARCHAR(256)          not null,
    SIZE                BIGINT                not null,
    DIGEST              VARCHAR(128)          not null,
    EDITOR_ID           VARCHAR(256)          not null,
    TIMESTAMP           BIGINT                not null,
    IS_AUTOSAVE         BOOLEAN default FALSE,
    KEEP_FOREVER        BOOLEAN default FALSE not null,
    CLONED_FROM_VERSION INTEGER default NULL,
    primary key (NODE_ID, VERSION)
);

create index REVISION_TABLE_INDEX_MIME_TYPE
    on REVISION (MIME_TYPE);

create index REVISION_TABLE_INDEX_VERSION
    on REVISION (VERSION);

create table SHARE
(
    NODE_ID     CHARACTER(36)        not null
        references NODE
            on delete cascade,
    RIGHTS      SMALLINT,
    TIMESTAMP   BIGINT               not null,
    TARGET_UUID VARCHAR(256)         not null,
    EXPIRE_DATE BIGINT,
    DIRECT      BOOLEAN default TRUE not null,
    primary key (NODE_ID, TARGET_UUID)
);

create index SHARE_TABLE_INDEX_TARGET_UUID
    on SHARE (TARGET_UUID);

create table TOMBSTONE
(
    NODE_ID   CHARACTER(36) not null,
    OWNER_ID  VARCHAR(256),
    TIMESTAMP BIGINT        not null,
    VERSION   INTEGER       not null,
    primary key (NODE_ID, VERSION)
);

create table TRASHED
(
    NODE_ID   CHARACTER(36) not null
        references NODE
            on delete cascade,
    PARENT_ID CHARACTER(36) not null,
    primary key (NODE_ID)
);
create index TRASHED_TABLE_INDEX_PARENT_ID
    on TRASHED (PARENT_ID);

INSERT INTO NODE
VALUES (NULL, 'LOCAL_ROOT', NULL, 'ROOT', 'ROOT', 0, '', 2, EXTRACT(EPOCH FROM NOW()),
        EXTRACT(EPOCH FROM NOW()), NULL,
        NULL, NULL, '', 0);
INSERT INTO NODE
VALUES (NULL, 'TRASH_ROOT', NULL, 'TRASH', 'ROOT', 0, '', 2, EXTRACT(EPOCH FROM NOW()),
        EXTRACT(EPOCH FROM NOW()), NULL,
        NULL, NULL, '', 0);
INSERT INTO DB_INFO
VALUES (1);
