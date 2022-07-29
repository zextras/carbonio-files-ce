// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;


import com.zextras.carbonio.files.Files.Config.Pagination;
import java.util.regex.Pattern;

/**
 * Represents a place to group all the possible constants and other values that can be useful for
 * all the Files classes. This interface is divided in other sub interfaces for a better
 * categorization.
 */
public interface Files {

  interface Config {

    interface Service {

      String URL  = "service.url";
      String PORT = "service.port";
    }

    interface Database {

      String URL  = "db.postgresql.url";
      String PORT = "db.postgresql.port";
    }

    interface UserManagement {

      String URL  = "carbonio.user-management.url";
      String PORT = "carbonio.user-management.port";
    }

    interface Storages {

      String URL  = "carbonio.storages.url";
      String PORT = "carbonio.storages.port";
    }

    interface Preview {

      String URL  = "carbonio.preview.url";
      String PORT = "carbonio.preview.port";
    }

    interface Mailbox {

      String URL  = "carbonio.mailbox.url";
      String PORT = "carbonio.mailbox.port";
    }

    interface Pagination {

      int LIMIT = 50;
    }

  }

  interface Db {

    short DB_VERSION = 2;

    /**
     * Names of Files tables
     */
    interface Tables {

      String DB_INFO                = "DB_INFO";
      String NODE                   = "NODE";
      String TRASHED_NODE           = "TRASHED";
      String NODE_CUSTOM_ATTRIBUTES = "CUSTOM";
      String FILE_VERSION           = "REVISION";
      String SHARE                  = "SHARE";
      String LINK                   = "LINK";
      String INVITATION_LINK        = "INVITATION_LINK";
      String TOMBSTONE              = "TOMBSTONE";
    }

    /**
     * Attributes name for the FILES.NODE table
     */
    interface Node {

      String ID              = "node_id";
      String OWNER_ID        = "owner_id";
      String CREATOR_ID      = "creator_id";
      String EDITOR_ID       = "editor_id";
      String PARENT_ID       = "folder_id";
      String ANCESTOR_IDS    = "ancestor_ids";
      String CREATED_AT      = "creation_timestamp";
      String UPDATED_AT      = "updated_timestamp";
      String TYPE            = "node_type";
      String CATEGORY        = "node_category";
      String NAME            = "name";
      String DESCRIPTION     = "description";
      String CURRENT_VERSION = "current_version";
      String INDEX_STATUS    = "index_status";
      String SIZE            = "size";
    }

    interface Trashed {

      String NODE_ID    = "node_id";
      String PARENT_ID  = "parent_id";
      String TRASHED_AT = "trashed_timestamp";
    }

    /**
     * Attributes name for the FILES.CUSTOM table
     */
    interface NodeCustomAttributes {

      String NODE_ID = "node_id";
      String USER_ID = "user_id";

      //The value remains "star" to preserve compatibility with old versions of DB
      String FLAG = "star";

      String COLOR = "color";
      String EXTRA = "extra";
    }

    /**
     * Attributes name for the FILES.REVISION table
     */
    interface FileVersion {

      String NODE_ID             = "node_id";
      String LAST_EDITOR_ID      = "editor_id";
      String UPDATED_AT          = "timestamp";
      String VERSION             = "version";
      String MIME_TYPE           = "mime_type";
      String SIZE                = "size";
      String DIGEST              = "digest";
      String IS_KEPT_FOREVER     = "keep_forever";
      String CLONED_FROM_VERSION = "cloned_from_version";
      String AUTOSAVE            = "is_autosave";
    }

    /**
     * Names of all the existing roots saved in the database.
     */
    interface RootId {

      String LOCAL_ROOT = "LOCAL_ROOT";
      String TRASH_ROOT = "TRASH_ROOT";
    }

    /**
     * Attributes name for the FILES.SHARE table
     */
    interface Share {

      String NODE_ID           = "node_id";
      String SHARE_TARGET_UUID = "target_uuid";
      String CREATED_AT        = "timestamp";
      String EXPIRED_AT        = "expire_date";
      String PERMISSIONS       = "rights";
      String DIRECT            = "direct";
    }

    /**
     * Attributes name for the FILES.LINK table
     */
    interface Link {

      String ID          = "id";
      String NODE_ID     = "node_id";
      String PUBLIC_ID   = "public_id";
      String CREATED_AT  = "created_at";
      String EXPIRES_AT  = "expire_at";
      String DESCRIPTION = "description";
    }

    /**
     * Attributes' names for the FILES.TOMBSTONE table
     */
    interface Tombstone {

      String NODE_ID   = "node_id";
      String OWNER_ID  = "owner_id";
      String TIMESTAMP = "timestamp";
      String VERSION   = "version";
      String VOLUME_ID = "volume_id";
    }

    /**
     * Attributes' names for the FILES.INVITATION_LINK table
     */
    interface InvitationLink {

      String ID            = "id";
      String NODE_ID       = "node_id";
      String INVITATION_ID = "invitation_id";
      String CREATED_AT    = "created_at";
      String PERMISSIONS   = "permissions";
    }
  }

  interface Cache {

    long DEFAULT_ITEM_LIFETIME_IN_MILLISEC = 60_000;
    long DEFAULT_SIZE                      = 1000;

    /**
     * Names of Files caches
     */
    String NODE         = "Node";
    String FILE_VERSION = "FileVersion";
    String SHARE        = "Share";
    String LINK         = "Link";
    String USER         = "User";
  }

  interface GraphQL {

    int    LIMIT_ELEMENTS_FOR_PAGE = Pagination.LIMIT;
    String ENTITY_TYPE             = "type";

    interface Context {

      String REQUESTER = "requester";
      String COOKIES   = "cookies";
    }

    /**
     * Names of Files GraphQL interfaces/types
     */
    interface Types {

      String NODE_INTERFACE    = "Node";
      String FILE              = "File";
      String FOLDER            = "Folder";
      String NODE_SORT         = "NodeSort";
      String NODE_PAGE         = "NodePage";
      String PERMISSIONS       = "Permissions";
      String USER              = "User";
      String DISTRIBUTION_LIST = "DistributionList";
      String SHARED_TARGET     = "SharedTarget";
      String ACCOUNT           = "Account";
      String SHARE_PERMISSION  = "SharePermission";
      String SHARE             = "Share";
      String LINK              = "Link";
      String INVITATION_LINK   = "InvitationLink";
      String NODE_TYPE         = "NodeType";
    }

    /**
     * Names of GraphQL data laoders
     */
    interface DataLoaders {

      String NODE_BATCH_LOADER  = "NodeBatchLoader";
      String SHARE_BATCH_LOADER = "ShareBatchLoader";
    }

    /**
     * Names of queries
     */
    interface Queries {

      String GET_NODE              = "getNode";
      String GET_USER              = "getUser";
      String GET_SHARE             = "getShare";
      String GET_ROOTS_LIST        = "getRootsList";
      String GET_PATH              = "getPath";
      String FIND_NODES            = "findNodes";
      String GET_VERSIONS          = "getVersions";
      String GET_LINKS             = "getLinks";
      String GET_INVITATION_LINKS  = "getInvitationLinks";
      String GET_ACCOUNT_BY_EMAIL  = "getAccountByEmail";
      String GET_ACCOUNTS_BY_EMAIL = "getAccountsByEmail";
      String GET_CONFIGS           = "getConfigs";
    }

    /**
     * Names of mutations
     */
    interface Mutations {

      String CREATE_FOLDER           = "createFolder";
      String UPDATE_NODE             = "updateNode";
      String FLAG_NODES              = "flagNodes";
      String TRASH_NODES             = "trashNodes";
      String RESTORE_NODES           = "restoreNodes";
      String MOVE_NODES              = "moveNodes";
      String DELETE_NODES            = "deleteNodes";
      String DELETE_VERSIONS         = "deleteVersions";
      String KEEP_VERSIONS           = "keepVersions";
      String CLONE_VERSION           = "cloneVersion";
      String CREATE_SHARE            = "createShare";
      String UPDATE_SHARE            = "updateShare";
      String DELETE_SHARE            = "deleteShare";
      String CREATE_LINK             = "createLink";
      String UPDATE_LINK             = "updateLink";
      String DELETE_LINKS            = "deleteLinks";
      String CREATE_INVITATION_LINK  = "createInvitationLink";
      String DELETE_INVITATION_LINKS = "deleteInvitationLinks";
      String COPY_NODES              = "copyNodes";
    }

    /**
     * Names of all GraphQL input parameters divided by queries
     */
    interface InputParameters {

      String NODE_ID    = "node_id";
      String LIMIT      = "limit";
      String CURSOR     = "cursor";
      String SORT       = "sort";
      String EMAIL      = "email";
      String PAGE_TOKEN = "page_token";

      interface CreateFolder {

        String PARENT_ID = "destination_id";
        String NAME      = "name";
      }

      interface UpdateNode {

        String NODE_ID             = InputParameters.NODE_ID;
        String NAME                = "name";
        String DESCRIPTION         = "description";
        String FLAGGED             = "flagged";
        String MARKED_FOR_DELETION = "marked_for_deletion";
      }

      interface FlagNodes {

        String NODE_IDS = "node_ids";
        String FLAG     = "flag";
      }

      interface FindNodes {

        String FLAGGED        = "flagged";
        String SHARED_BY_ME   = "shared_by_me";
        String SHARED_WITH_ME = "shared_with_me";
        String DIRECT_SHARE   = "direct_share";
        String FOLDER_ID      = "folder_id";
        String CASCADE        = "cascade";
        String SKIP           = "skip";
        String LIMIT          = "limit";
        String SORT           = "sort";
        String PAGE_TOKEN     = "page_token";
        String KEYWORDS       = "keywords";
      }

      interface GetVersions {

        String NODE_ID  = "node_id";
        String VERSIONS = "versions";
      }

      interface CopyNodes {

        String NODE_IDS       = "node_ids";
        String DESTINATION_ID = "destination_id";
      }

      interface MoveNodes {

        String NODE_IDS       = "node_ids";
        String DESTINATION_ID = "destination_id";
      }

      interface DeleteNodes {

        String NODE_IDS = "node_ids";
      }

      interface KeepVersions {

        String KEEP_FOREVER = "keep_forever";

      }

      interface CloneVersion {

        String NODE_ID = "node_id";
        String VERSION = "version";
      }

      interface Share {

        String NODE_ID         = "node_id";
        String SHARE_TARGET_ID = "share_target_id";
        String PERMISSION      = "permission";
        String EXPIRES_AT      = "expires_at";
        String CUSTOM_MESSAGE  = "custom_message";
      }

      interface Link {

        String LINK_ID     = "link_id";
        String NODE_ID     = "node_id";
        String EXPIRES_AT  = "expires_at";
        String DESCRIPTION = "description";
        String LINK_IDS    = "link_ids";
      }

      interface TrashNodes {

        String NODE_IDS = "node_ids";
      }

      interface RestoreNodes {

        String NODE_IDS = "node_ids";
      }

      interface GetUser {

        String USER_ID = "user_id";
        String EMAIL   = "email";
      }

      interface GetAccountsByEmail {

        String EMAILS = "emails";
      }
    }

    /**
     * Attributes name for the type User
     */
    interface User {

      String ID        = "id";
      String EMAIL     = "email";
      String FULL_NAME = "full_name";
    }

    /**
     * Attributes name for the type Distribution List
     */
    interface DistributionList {

      String ID    = "id";
      String NAME  = "name";
      String USERS = "users";
    }

    /**
     * Attributes name for the type Node/File/Folder
     */
    interface Node {

      String ID               = "id";
      String CREATED_AT       = "created_at";
      String CREATOR          = "creator";
      String OWNER            = "owner";
      String LAST_EDITOR      = "last_editor";
      String UPDATED_AT       = "updated_at";
      String PERMISSIONS      = "permissions";
      String NAME             = "name";
      String EXTENSION        = "extension";
      String DESCRIPTION      = "description";
      String TYPE             = "type";
      String FLAGGED          = "flagged";
      String PARENT           = "parent";
      String ROOT_ID          = "rootId";
      String SHARES           = "shares";
      String LINKS            = "links";
      String INVITATION_LINKS = "invitation_links";
    }

    /**
     * Attributes name specific for the type File
     */
    interface FileVersion extends Node {

      String LAST_EDITOR         = "last_editor";
      String UPDATED_AT          = "updated_at";
      String VERSION             = "version";
      String MIME_TYPE           = "mime_type";
      String SIZE                = "size";
      String KEEP_FOREVER        = "keep_forever";
      String CLONED_FROM_VERSION = "cloned_from_version";
      String DIGEST              = "digest";
    }

    /**
     * Attributes name specific for the type Folder
     */
    interface Folder extends Node {

      String CHILDREN = "children";
    }

    /**
     * Attributes name specific for the type NodePage
     */
    interface NodePage {

      String NODES      = "nodes";
      String PAGE_TOKEN = "page_token";
    }

    /**
     * Attributes name specific for the type Permissions
     */
    interface Permissions {

      String CAN_READ         = "can_read";
      String CAN_WRITE_FILE   = "can_write_file";
      String CAN_WRITE_FOLDER = "can_write_folder";
      String CAN_DELETE       = "can_delete";
      String CAN_ADD_VERSION  = "can_add_version";
      String CAN_READ_LINK    = "can_read_link";
      String CAN_CHANGE_LINK  = "can_change_link";
      String CAN_SHARE        = "can_share";
      String CAN_READ_SHARE   = "can_read_share";
      String CAN_CHANGE_SHARE = "can_change_share";
    }

    /**
     * Attributes name for the type Share
     */
    interface Share {

      String CREATED_AT   = "created_at";
      String NODE         = "node";
      String SHARE_TARGET = "share_target";
      String PERMISSION   = "permission";
      String EXPIRES_AT   = "expires_at";
    }

    /**
     * Attributes name for the type Link
     */
    interface Link {

      String ID          = "id";
      String URL         = "url";
      String NODE        = "node";
      String CREATED_AT  = "created_at";
      String EXPIRES_AT  = "expires_at";
      String DESCRIPTION = "description";
    }

    interface Config {

      String NAME  = "name";
      String VALUE = "value";
    }
  }

  interface API {

    interface Endpoints {

      String SERVICE             = "/";
      String PUBLIC_LINK_URL     = "/services/files/link/";
      String INVITATION_LINK_URL = "/services/files/invite/";

      Pattern HEALTH              = Pattern.compile(SERVICE + "health/?(live|ready)?/?$");
      Pattern HEALTH_LIVE         = Pattern.compile(SERVICE + "health/live/?$");
      Pattern HEALTH_READY        = Pattern.compile(SERVICE + "health/ready/?$");
      Pattern GRAPHQL             = Pattern.compile(SERVICE + "graphql/?$");
      Pattern UPLOAD_FILE         = Pattern.compile(SERVICE + "upload/?$");
      Pattern UPLOAD_FILE_VERSION = Pattern.compile(SERVICE + "upload-version/?$");
      Pattern UPLOAD_FILE_TO      = Pattern.compile(SERVICE + "upload-to/?$");
      Pattern DOWNLOAD_FILE       = Pattern.compile(
        SERVICE + "download/([a-f0-9\\\\-]*)/?([0-9]+)?/?$");
      Pattern PUBLIC_LINK         = Pattern.compile(SERVICE + "link/([a-zA-Z0-9]{8})/?$");
      Pattern INVITATION_LINK     = Pattern.compile(SERVICE + "invite/([a-zA-Z0-9]{8})/?$");

      Pattern PREVIEW            = Pattern.compile(SERVICE + "preview/(.*)");
      Pattern PREVIEW_IMAGE      = Pattern.compile(
        SERVICE
          + "preview/image/([a-f0-9\\-]*)/([0-9]+)/([0-9]*x[0-9]*)/?((?=(?!thumbnail))(?=([^/\\n ]*)))"
      );
      Pattern THUMBNAIL_IMAGE    = Pattern.compile(
        SERVICE + "preview/image/([a-f0-9\\-]*)/([0-9]+)/([0-9]*x[0-9]*)/thumbnail/?\\??(.*)"
      );
      Pattern PREVIEW_PDF        = Pattern.compile(
        SERVICE + "preview/pdf/([a-f0-9\\-]*)/([0-9]+)/?((?=(?!thumbnail))(?=([^/\\n ]*)))"
      );
      Pattern THUMBNAIL_PDF      = Pattern.compile(
        SERVICE + "preview/pdf/([a-f0-9\\-]*)/([0-9]+)/([0-9]*x[0-9]*)/thumbnail/?\\??(.*)"
      );
      Pattern PREVIEW_DOCUMENT   = Pattern.compile(
        SERVICE + "preview/document/([a-f0-9\\-]*)/([0-9]+)/?((?=(?!thumbnail))(?=([^/\\n ]*)))"
      );
      Pattern THUMBNAIL_DOCUMENT = Pattern.compile(
        SERVICE + "preview/document/([a-f0-9\\-]*)/([0-9]+)/([0-9]*x[0-9]*)/thumbnail/?\\??(.*)"
      );
    }

    interface Headers {

      String UPLOAD_FILENAME          = "Filename";
      String UPLOAD_DESCRIPTION       = "Description";
      String UPLOAD_PARENT_ID         = "ParentId";
      String UPLOAD_NODE_ID           = "NodeId";
      String UPLOAD_OVERWRITE_VERSION = "OverwriteVersion";
    }

    interface ContextAttribute {

      String REQUESTER = "requester";
      String COOKIES   = "cookies";
    }
  }

  interface ServiceDiscover {

    String SERVICE_NAME = "carbonio-files";

    interface Config {

      String MAX_VERSIONS                          = "max-number-of-versions";
      int    DEFAULT_MAX_VERSIONS                  = 30;
      String MAX_KEEP_VERSIONS                     = "max-number-of-keep-versions";
      int    DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION = 2;
      int    DEFAULT_MAX_KEEP_VERSIONS             =
        DEFAULT_MAX_VERSIONS - DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;

      interface Db {

        String NAME             = "db-name";
        String DEFAULT_NAME     = "carbonio-files-db";
        String USERNAME         = "db-username";
        String DEFAULT_USERNAME = "carbonio-files-db";
        String PASSWORD         = "db-password";
      }
    }

  }

}
