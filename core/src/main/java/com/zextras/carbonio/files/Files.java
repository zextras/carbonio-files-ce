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
public final class Files {

  private Files() {}

  public static final class Config {

    private Config() {}

    public static final class Service {

      private Service() {}

      public static final String URL  = "service.url";
      public static final String PORT = "service.port";
    }

    public static final class Database {

      private Database() {}

      public static final String URL  = "db.postgresql.url";
      public static final String PORT = "db.postgresql.port";
    }

    public static final class UserManagement {

      private UserManagement() {}

      public static final String URL  = "carbonio.user-management.url";
      public static final String PORT = "carbonio.user-management.port";
    }

    public static final class Storages {

      private Storages() {}

      public static final String URL  = "carbonio.storages.url";
      public static final String PORT = "carbonio.storages.port";
    }

    public static final class Preview {

      private Preview() {}

      public static final String URL  = "carbonio.preview.url";
      public static final String PORT = "carbonio.preview.port";
    }

    public static final class Mailbox {

      private Mailbox() {}

      public static final String URL  = "carbonio.mailbox.url";
      public static final String PORT = "carbonio.mailbox.port";
    }

    public static final class Pagination {

      private Pagination() {}

      public static final int LIMIT = 50;
    }

    public static final class Hikari {

      public static final int MAX_POOL_SIZE        = 2;
      public static final int MIN_IDLE_CONNECTIONS = 1;

      private Hikari() {}
    }

  }

  public static final class Db {

    private Db() {}

    public static final short DB_VERSION = 2;

    /**
     * Names of Files tables
     */
    public static final class Tables {

      private Tables() {}

      public static final String DB_INFO                = "DB_INFO";
      public static final String NODE                   = "NODE";
      public static final String TRASHED_NODE           = "TRASHED";
      public static final String NODE_CUSTOM_ATTRIBUTES = "CUSTOM";
      public static final String FILE_VERSION           = "REVISION";
      public static final String SHARE                  = "SHARE";
      public static final String LINK                   = "LINK";
      public static final String COLLABORATION_LINK     = "COLLABORATION_LINK";
      public static final String TOMBSTONE              = "TOMBSTONE";
    }

    /**
     * Attributes name for the FILES.NODE table
     */
    public static final class Node {

      private Node() {}

      public static final String ID              = "node_id";
      public static final String OWNER_ID        = "owner_id";
      public static final String CREATOR_ID      = "creator_id";
      public static final String EDITOR_ID       = "editor_id";
      public static final String PARENT_ID       = "folder_id";
      public static final String ANCESTOR_IDS    = "ancestor_ids";
      public static final String CREATED_AT      = "creation_timestamp";
      public static final String UPDATED_AT      = "updated_timestamp";
      public static final String TYPE            = "node_type";
      public static final String CATEGORY        = "node_category";
      public static final String NAME            = "name";
      public static final String DESCRIPTION     = "description";
      public static final String CURRENT_VERSION = "current_version";
      public static final String INDEX_STATUS    = "index_status";
      public static final String SIZE            = "size";
    }

    public static final class Trashed {

      private Trashed() {}

      public static final String NODE_ID   = "node_id";
      public static final String PARENT_ID = "parent_id";
    }

    /**
     * Attributes name for the FILES.CUSTOM table
     */
    public static final class NodeCustomAttributes {

      private NodeCustomAttributes() {}

      public static final String NODE_ID = "node_id";
      public static final String USER_ID = "user_id";

      //The value remains "star" to preserve compatibility with old versions of DB
      public static final String FLAG = "star";

      public static final String COLOR = "color";
      public static final String EXTRA = "extra";
    }

    /**
     * Attributes name for the FILES.REVISION table
     */
    public static final class FileVersion {

      private FileVersion() {}

      public static final String NODE_ID             = "node_id";
      public static final String LAST_EDITOR_ID      = "editor_id";
      public static final String UPDATED_AT          = "timestamp";
      public static final String VERSION             = "version";
      public static final String MIME_TYPE           = "mime_type";
      public static final String SIZE                = "size";
      public static final String DIGEST              = "digest";
      public static final String IS_KEPT_FOREVER     = "keep_forever";
      public static final String CLONED_FROM_VERSION = "cloned_from_version";
      public static final String AUTOSAVE            = "is_autosave";
    }

    /**
     * Names of all the existing roots saved in the database.
     */
    public static final class RootId {

      private RootId() {}

      public static final String LOCAL_ROOT = "LOCAL_ROOT";
      public static final String TRASH_ROOT = "TRASH_ROOT";
    }

    /**
     * Attributes name for the FILES.SHARE table
     */
    public static final class Share {

      private Share() {}

      public static final String NODE_ID           = "node_id";
      public static final String SHARE_TARGET_UUID = "target_uuid";
      public static final String CREATED_AT        = "timestamp";
      public static final String EXPIRED_AT        = "expire_date";
      public static final String PERMISSIONS       = "rights";
      public static final String DIRECT            = "direct";
      public static final String CREATED_VIA_LINK  = "created_via_link";
    }

    /**
     * Attributes name for the FILES.LINK table
     */
    public static final class Link {

      private Link() {}

      public static final String ID          = "id";
      public static final String NODE_ID     = "node_id";
      public static final String PUBLIC_ID   = "public_id";
      public static final String CREATED_AT  = "created_at";
      public static final String EXPIRES_AT  = "expire_at";
      public static final String DESCRIPTION = "description";
    }

    /**
     * Attributes' names for the FILES.TOMBSTONE table
     */
    public static final class Tombstone {

      private Tombstone() {}

      public static final String NODE_ID   = "node_id";
      public static final String OWNER_ID  = "owner_id";
      public static final String TIMESTAMP = "timestamp";
      public static final String VERSION   = "version";
    }

    /**
     * Attributes' names for the FILES.COLLABORATION_LINK table
     */
    public static final class CollaborationLink {

      private CollaborationLink() {}

      public static final String ID            = "id";
      public static final String NODE_ID       = "node_id";
      public static final String INVITATION_ID = "invitation_id";
      public static final String CREATED_AT    = "created_at";
      public static final String PERMISSIONS   = "permissions";
    }
  }

  public static final class Cache {

    private Cache() {}

    public static final long DEFAULT_ITEM_LIFETIME_IN_MILLIS = 60_000;
    public static final long DEFAULT_SIZE                    = 1000;

    /**
     * Names of Files caches
     */
    public static final String NODE         = "Node";
    public static final String FILE_VERSION = "FileVersion";
    public static final String SHARE        = "Share";
    public static final String LINK         = "Link";
    public static final String USER         = "User";
  }

  public static final class GraphQL {

    private GraphQL() {}

    public static final int    LIMIT_ELEMENTS_FOR_PAGE = Pagination.LIMIT;
    public static final String ENTITY_TYPE             = "type";

    public static final class Context {

      private Context() {}

      public static final String REQUESTER = "requester";
      public static final String COOKIES   = "cookies";
    }

    /**
     * Names of Files GraphQL interfaces/types
     */
    public static final class Types {

      private Types() {}

      public static final String NODE_INTERFACE     = "Node";
      public static final String FILE               = "File";
      public static final String FOLDER             = "Folder";
      public static final String NODE_SORT          = "NodeSort";
      public static final String NODE_PAGE          = "NodePage";
      public static final String PERMISSIONS        = "Permissions";
      public static final String USER               = "User";
      public static final String DISTRIBUTION_LIST  = "DistributionList";
      public static final String SHARED_TARGET      = "SharedTarget";
      public static final String ACCOUNT            = "Account";
      public static final String SHARE_PERMISSION   = "SharePermission";
      public static final String SHARE              = "Share";
      public static final String LINK               = "Link";
      public static final String COLLABORATION_LINK = "CollaborationLink";
      public static final String NODE_TYPE          = "NodeType";
    }

    /**
     * Names of GraphQL data loaders
     */
    public static final class DataLoaders {

      private DataLoaders() {}

      public static final String NODE_BATCH_LOADER  = "NodeBatchLoader";
      public static final String SHARE_BATCH_LOADER = "ShareBatchLoader";
    }

    /**
     * Names of queries
     */
    public static final class Queries {

      private Queries() {}

      public static final String GET_NODE                = "getNode";
      public static final String GET_USER                = "getUser";
      public static final String GET_SHARE               = "getShare";
      public static final String GET_ROOTS_LIST          = "getRootsList";
      public static final String GET_PATH                = "getPath";
      public static final String FIND_NODES              = "findNodes";
      public static final String GET_VERSIONS            = "getVersions";
      public static final String GET_LINKS               = "getLinks";
      public static final String GET_COLLABORATION_LINKS = "getCollaborationLinks";
      public static final String GET_ACCOUNT_BY_EMAIL    = "getAccountByEmail";
      public static final String GET_ACCOUNTS_BY_EMAIL   = "getAccountsByEmail";
      public static final String GET_CONFIGS             = "getConfigs";
    }

    /**
     * Names of mutations
     */
    public static final class Mutations {

      private Mutations() {}

      public static final String CREATE_FOLDER              = "createFolder";
      public static final String UPDATE_NODE                = "updateNode";
      public static final String FLAG_NODES                 = "flagNodes";
      public static final String TRASH_NODES                = "trashNodes";
      public static final String RESTORE_NODES              = "restoreNodes";
      public static final String MOVE_NODES                 = "moveNodes";
      public static final String DELETE_NODES               = "deleteNodes";
      public static final String DELETE_VERSIONS            = "deleteVersions";
      public static final String KEEP_VERSIONS              = "keepVersions";
      public static final String CLONE_VERSION              = "cloneVersion";
      public static final String CREATE_SHARE               = "createShare";
      public static final String UPDATE_SHARE               = "updateShare";
      public static final String DELETE_SHARE               = "deleteShare";
      public static final String CREATE_LINK                = "createLink";
      public static final String UPDATE_LINK                = "updateLink";
      public static final String DELETE_LINKS               = "deleteLinks";
      public static final String CREATE_COLLABORATION_LINK  = "createCollaborationLink";
      public static final String DELETE_COLLABORATION_LINKS = "deleteCollaborationLinks";
      public static final String COPY_NODES                 = "copyNodes";
    }

    /**
     * Names of all GraphQL input parameters divided by queries
     */
    public static final class InputParameters {

      private InputParameters() {}

      public static final String NODE_ID    = "node_id";
      public static final String LIMIT      = "limit";
      public static final String CURSOR     = "cursor";
      public static final String SORT       = "sort";
      public static final String EMAIL      = "email";
      public static final String PAGE_TOKEN = "page_token";

      public static final class CreateFolder {

        private CreateFolder() {}

        public static final String PARENT_ID = "destination_id";
        public static final String NAME      = "name";
      }

      public static final class UpdateNode {

        private UpdateNode() {}

        public static final String NODE_ID             = InputParameters.NODE_ID;
        public static final String NAME                = "name";
        public static final String DESCRIPTION         = "description";
        public static final String FLAGGED             = "flagged";
        public static final String MARKED_FOR_DELETION = "marked_for_deletion";
      }

      public static final class FlagNodes {

        private FlagNodes() {}

        public static final String NODE_IDS = "node_ids";
        public static final String FLAG     = "flag";
      }

      public static final class FindNodes {

        private FindNodes() {}

        public static final String FLAGGED        = "flagged";
        public static final String SHARED_BY_ME   = "shared_by_me";
        public static final String SHARED_WITH_ME = "shared_with_me";
        public static final String DIRECT_SHARE   = "direct_share";
        public static final String FOLDER_ID      = "folder_id";
        public static final String CASCADE        = "cascade";
        public static final String SKIP           = "skip";
        public static final String LIMIT          = "limit";
        public static final String SORT           = "sort";
        public static final String PAGE_TOKEN     = "page_token";
        public static final String KEYWORDS       = "keywords";
        public static final String NODE_TYPE      = "type";
        public static final String OWNER_ID       = "owner_id";
      }

      public static final class GetVersions {

        private GetVersions() {}

        public static final String NODE_ID  = "node_id";
        public static final String VERSIONS = "versions";
      }

      public static final class CopyNodes {

        private CopyNodes() {}

        public static final String NODE_IDS       = "node_ids";
        public static final String DESTINATION_ID = "destination_id";
      }

      public static final class MoveNodes {

        private MoveNodes() {}

        public static final String NODE_IDS       = "node_ids";
        public static final String DESTINATION_ID = "destination_id";
      }

      public static final class DeleteNodes {

        private DeleteNodes() {}

        public static final String NODE_IDS = "node_ids";
      }

      public static final class KeepVersions {

        private KeepVersions() {}

        public static final String KEEP_FOREVER = "keep_forever";

      }

      public static final class CloneVersion {

        private CloneVersion() {}

        public static final String NODE_ID = "node_id";
        public static final String VERSION = "version";
      }

      public static final class Share {

        private Share() {}

        public static final String NODE_ID         = "node_id";
        public static final String SHARE_TARGET_ID = "share_target_id";
        public static final String PERMISSION      = "permission";
        public static final String EXPIRES_AT      = "expires_at";
        public static final String CUSTOM_MESSAGE  = "custom_message";
      }

      public static final class Link {

        private Link() {}

        public static final String LINK_ID     = "link_id";
        public static final String NODE_ID     = "node_id";
        public static final String EXPIRES_AT  = "expires_at";
        public static final String DESCRIPTION = "description";
        public static final String LINK_IDS    = "link_ids";
      }

      public static final class TrashNodes {

        private TrashNodes() {}

        public static final String NODE_IDS = "node_ids";
      }

      public static final class RestoreNodes {

        private RestoreNodes() {}

        public static final String NODE_IDS = "node_ids";
      }

      public static final class GetUser {

        private GetUser() {}

        public static final String USER_ID = "user_id";
        public static final String EMAIL   = "email";
      }

      public static final class GetAccountsByEmail {

        private GetAccountsByEmail() {}

        public static final String EMAILS = "emails";
      }

      public static final class CreateCollaborationLink {

        private CreateCollaborationLink() {}

        public static final String NODE_ID    = InputParameters.NODE_ID;
        public static final String PERMISSION = "permission";
      }

      public static final class GetCollaborationLink {

        private GetCollaborationLink() {}

        public static final String NODE_ID = InputParameters.NODE_ID;
      }

      public static final class DeleteCollaborationLinks {

        private DeleteCollaborationLinks() {}

        public static final String COLLABORATION_LINK_IDS = "collaboration_link_ids";
      }
    }

    /**
     * Attributes name for the type User
     */
    public static final class User {

      private User() {}

      public static final String ID        = "id";
      public static final String EMAIL     = "email";
      public static final String FULL_NAME = "full_name";
    }

    /**
     * Attributes name for the type Distribution List
     */
    public static final class DistributionList {

      private DistributionList() {}

      public static final String ID    = "id";
      public static final String NAME  = "name";
      public static final String USERS = "users";
    }

    /**
     * Attributes name for the type Node/File/Folder
     */
    public static class Node {

      private Node() {}

      public static final String ID                  = "id";
      public static final String CREATED_AT          = "created_at";
      public static final String CREATOR             = "creator";
      public static final String OWNER               = "owner";
      public static final String LAST_EDITOR         = "last_editor";
      public static final String UPDATED_AT          = "updated_at";
      public static final String PERMISSIONS         = "permissions";
      public static final String NAME                = "name";
      public static final String EXTENSION           = "extension";
      public static final String DESCRIPTION         = "description";
      public static final String TYPE                = "type";
      public static final String FLAGGED             = "flagged";
      public static final String PARENT              = "parent";
      public static final String ROOT_ID             = "rootId";
      public static final String SHARES              = "shares";
      public static final String LINKS               = "links";
      public static final String COLLABORATION_LINKS = "collaboration_links";
    }

    /**
     * Attributes name specific for the type File
     */
    public static final class FileVersion extends Node {

      private FileVersion() {}

      public static final String LAST_EDITOR         = "last_editor";
      public static final String UPDATED_AT          = "updated_at";
      public static final String VERSION             = "version";
      public static final String MIME_TYPE           = "mime_type";
      public static final String SIZE                = "size";
      public static final String KEEP_FOREVER        = "keep_forever";
      public static final String CLONED_FROM_VERSION = "cloned_from_version";
      public static final String DIGEST              = "digest";
    }

    /**
     * Attributes name specific for the type Folder
     */
    public static final class Folder extends Node {

      private Folder() {}

      public static final String CHILDREN = "children";
    }

    /**
     * Attributes name specific for the type NodePage
     */
    public static final class NodePage {

      private NodePage() {}

      public static final String NODES      = "nodes";
      public static final String PAGE_TOKEN = "page_token";
    }

    /**
     * Attributes name for the type Share
     */
    public static final class Share {

      private Share() {}

      public static final String CREATED_AT   = "created_at";
      public static final String NODE         = "node";
      public static final String SHARE_TARGET = "share_target";
      public static final String PERMISSION   = "permission";
      public static final String EXPIRES_AT   = "expires_at";
    }

    /**
     * Attributes name for the type Link
     */
    public static final class Link {

      private Link() {}

      public static final String ID          = "id";
      public static final String URL         = "url";
      public static final String NODE        = "node";
      public static final String CREATED_AT  = "created_at";
      public static final String EXPIRES_AT  = "expires_at";
      public static final String DESCRIPTION = "description";
    }

    /**
     * Attributes name for the type Collaboration Link
     */
    public static final class CollaborationLink {

      private CollaborationLink() {}

      public static final String ID         = "id";
      public static final String FULL_URL   = "url";
      public static final String NODE       = "node";
      public static final String CREATED_AT = "created_at";
      public static final String PERMISSION = "permission";
    }

    public static final class Config {

      private Config() {}

      public static final String NAME  = "name";
      public static final String VALUE = "value";
    }
  }

  public static final class API {

    private API() {}

    public static final class Endpoints {

      private Endpoints() {}

      public static final String SERVICE                = "/";
      public static final String PUBLIC_LINK_URL        = "/services/files/link/";
      public static final String COLLABORATION_LINK_URL = "/services/files/invite/";

      public static final Pattern METRICS             = Pattern.compile(SERVICE + "metrics/?$");
      public static final Pattern HEALTH              = Pattern.compile(
        SERVICE + "health/?(live|ready)?/?$");
      public static final Pattern HEALTH_LIVE         = Pattern.compile(SERVICE + "health/live/?$");
      public static final Pattern HEALTH_READY        = Pattern.compile(
        SERVICE + "health/ready/?$");
      public static final Pattern GRAPHQL             = Pattern.compile(SERVICE + "graphql/?$");
      public static final Pattern UPLOAD_FILE         = Pattern.compile(SERVICE + "upload/?$");
      public static final Pattern UPLOAD_FILE_VERSION = Pattern.compile(
        SERVICE + "upload-version/?$");
      public static final Pattern UPLOAD_FILE_TO      = Pattern.compile(SERVICE + "upload-to/?$");
      public static final Pattern DOWNLOAD_FILE       = Pattern.compile(
        SERVICE + "download/([a-f\\d\\-]*)/?([\\d]+)?/?$");
      public static final Pattern PUBLIC_LINK         = Pattern.compile(
        SERVICE + "link/([\\w]{8})/?$");
      public static final Pattern COLLABORATION_LINK  = Pattern.compile(
        SERVICE + "invite/([\\w]{8})/?$");

      public static final Pattern PREVIEW            = Pattern.compile(SERVICE + "preview/(.*)");
      public static final Pattern PREVIEW_IMAGE      = Pattern.compile(
        SERVICE
          + "preview/image/([a-f\\d\\-]*)/([\\d]+)/([\\d]*x[\\d]*)/?((?=(?!thumbnail))(?=([^/\\n ]*)))"
      );
      public static final Pattern THUMBNAIL_IMAGE    = Pattern.compile(
        SERVICE + "preview/image/([a-f\\d\\-]*)/([\\d]+)/([\\d]*x[\\d]*)/thumbnail/?\\??(.*)"
      );
      public static final Pattern PREVIEW_PDF        = Pattern.compile(
        SERVICE + "preview/pdf/([a-f\\d\\-]*)/([\\d]+)/?((?=(?!thumbnail))(?=([^/\\n ]*)))"
      );
      public static final Pattern THUMBNAIL_PDF      = Pattern.compile(
        SERVICE + "preview/pdf/([a-f\\d\\-]*)/([\\d]+)/([\\d]*x[\\d]*)/thumbnail/?\\??(.*)"
      );
      public static final Pattern PREVIEW_DOCUMENT   = Pattern.compile(
        SERVICE + "preview/document/([a-f\\d\\-]*)/([\\d]+)/?((?=(?!thumbnail))(?=([^/\\n ]*)))"
      );
      public static final Pattern THUMBNAIL_DOCUMENT = Pattern.compile(
        SERVICE + "preview/document/([a-f\\d\\-]*)/([\\d]+)/([\\d]*x[\\d]*)/thumbnail/?\\??(.*)"
      );
    }

    public static final class Headers {

      private Headers() {}

      public static final String UPLOAD_FILENAME          = "Filename";
      public static final String UPLOAD_DESCRIPTION       = "Description";
      public static final String UPLOAD_PARENT_ID         = "ParentId";
      public static final String UPLOAD_NODE_ID           = "NodeId";
      public static final String UPLOAD_OVERWRITE_VERSION = "OverwriteVersion";
      public static final String COOKIE_ZM_AUTH_TOKEN     = "ZM_AUTH_TOKEN";
    }

    public static final class ContextAttribute {

      private ContextAttribute() {}

      public static final String REQUESTER = "requester";
      public static final String COOKIES   = "cookies";
    }
  }

  public static final class ServiceDiscover {

    private ServiceDiscover() {}

    public static final String SERVICE_NAME = "carbonio-files";

    public static final class Config {

      private Config() {}

      public static final String MAX_VERSIONS                          = "max-number-of-versions";
      public static final int    DEFAULT_MAX_VERSIONS                  = 30;
      public static final String MAX_KEEP_VERSIONS                     = "max-number-of-keep-versions";
      public static final int    DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION = 2;
      public static final int    DEFAULT_MAX_KEEP_VERSIONS             =
        DEFAULT_MAX_VERSIONS - DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;

      public static final class Db {

        private Db() {}

        public static final String NAME                        = "db-name";
        public static final String DEFAULT_NAME                = "carbonio-files-db";
        public static final String USERNAME                    = "db-username";
        public static final String DEFAULT_USERNAME            = "carbonio-files-db";
        public static final String PASSWORD                    = "db-password";
        public static final String HIKARI_MAX_POOL_SIZE        = "hikari-max-pool-size";
        public static final String HIKARI_MIN_IDLE_CONNECTIONS = "hikari-min-idle-connections";
      }
    }
  }
}
