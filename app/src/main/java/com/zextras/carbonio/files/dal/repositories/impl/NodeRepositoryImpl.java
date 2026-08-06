// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants.Config.Pagination;
import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SortOrder;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clean Panache/Hibernate implementation of {@link NodeRepository}. This is a from-scratch,
 * idiomatic HQL + native-SQL rewrite of the old Ebean {@code NodeRepositoryEbean}: it does NOT port
 * the Ebean SQL-AST (SearchBuilder / SQLExpression / PageQuery / FindNodeKeySetBuilder). It honours
 * only the {@link NodeRepository} contract and its Javadoc semantics.
 *
 * <p>Notable design choices:
 *
 * <ul>
 *   <li>{@code node_id}/{@code folder_id} are {@code CHARACTER(36)} (blank padded). Any external id
 *       used in an equality against those columns is normalised to 36 chars ({@link
 *       #normalizeId(String)}) so a bound {@code varchar} parameter matches the stored value.
 *   <li>Visibility is expressed with correlated {@code EXISTS} subqueries against {@code Share} /
 *       {@code NodeCustomAttributes} rather than to-many joins, which keeps result rows unique (no
 *       {@code distinct} needed) and reads cleanly.
 *   <li>{@link #findNodes} uses keyset (seek) pagination. The page token is an opaque Base64-URL
 *       JSON blob ({@link PageToken}) carrying the full search criteria plus the cursor (the last
 *       row's value for every applied sort column). Decoding re-applies the identical criteria plus
 *       a lexicographic {@code (sortKeys, id) > cursor} predicate. {@code node_id} is always the
 *       final tiebreaker, giving a strict total order &rarr; pages never overlap and never gap.
 * </ul>
 */
@ApplicationScoped
public class NodeRepositoryImpl implements NodeRepository {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Logger logger = LoggerFactory.getLogger(NodeRepositoryImpl.class);

  /** Maps a {@link Db.Node} sortable column name to the JPA entity field of {@link Node}. */
  private static final Map<String, String> FIELD_BY_COLUMN =
      Map.of(
          Db.Node.ID, "mId",
          Db.Node.EDITOR_ID, "mLastEditorId",
          Db.Node.NAME, "mName",
          Db.Node.OWNER_ID, "mOwnerId",
          Db.Node.CATEGORY, "mNodeCategory",
          Db.Node.UPDATED_AT, "mUpdatedAt",
          Db.Node.CREATED_AT, "mCreatedAt",
          Db.Node.SIZE, "mSize");

  @Inject EntityManager entityManager;
  @Inject CollationRepository collationRepository;
  @Inject FilesConfig filesConfig;

  // ---------------------------------------------------------------------------------------------
  // Simple finders
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<Node> getNode(String nodeId) {
    return Optional.ofNullable(entityManager.find(Node.class, normalizeId(nodeId)));
  }

  @Override
  public Optional<Node> getNodeForUpdate(String nodeId) {
    // The caller MUST already have an active transaction; the pessimistic lock lives until it ends.
    return Optional.ofNullable(
        entityManager.find(Node.class, normalizeId(nodeId), LockModeType.PESSIMISTIC_WRITE));
  }

  @Override
  public Optional<Node> getNodeByName(String nodeName, String folderId, String nodeOwner) {
    // findList().findFirst() rather than a single-result query: defensive against (illegal) dupes.
    return entityManager
        .createQuery(
            "select n from Node n where n.mName = :name and n.mParentId = :parent and n.mOwnerId ="
                + " :owner",
            Node.class)
        .setParameter("name", nodeName)
        .setParameter("parent", normalizeId(folderId))
        .setParameter("owner", nodeOwner)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public List<Node> getRootsList() {
    return entityManager
        .createQuery("select n from Node n where n.mNodeCategory = 0", Node.class)
        .getResultList();
  }

  @Override
  public List<Node> findNodesByOwner(String ownerId) {
    return entityManager
        .createQuery("select n from Node n where n.mOwnerId = :owner", Node.class)
        .setParameter("owner", ownerId)
        .getResultList();
  }

  @Override
  public Optional<Node> findFirstByOwner(String ownerId) {
    return entityManager
        .createQuery("select n from Node n where n.mOwnerId = :owner", Node.class)
        .setParameter("owner", ownerId)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public Stream<Node> getNodes(List<String> nodeIds, Optional<NodeSort> sort) {
    if (nodeIds.isEmpty()) {
      return Stream.empty();
    }
    List<String> ids = nodeIds.stream().map(NodeRepositoryImpl::normalizeId).toList();
    String orderBy = sort.map(s -> " order by " + orderFragment(s) + ", n.mId asc").orElse("");
    return entityManager
        .createQuery("select n from Node n where n.mId in :ids" + orderBy, Node.class)
        .setParameter("ids", ids)
        .getResultList()
        .stream();
  }

  @Override
  public List<String> getChildrenIds(
      String nodeId, Optional<NodeSort> sort, Optional<String> userId, boolean showMarked) {
    Map<String, Object> params = new HashMap<>();
    StringBuilder hql = new StringBuilder("select n.mId from Node n where n.mParentId = :parentId");
    params.put("parentId", normalizeId(nodeId));

    if (RootId.LOCAL_ROOT.equals(nodeId) && userId.isPresent()) {
      hql.append(" and n.mOwnerId = :ownerId");
      params.put("ownerId", userId.get());
    }
    if (!showMarked) {
      // Exclude children currently marked for deletion (i.e. present in the TRASHED table).
      hql.append(" and not exists (select 1 from TrashedNode tn where tn.mNodeId = n.mId)");
    }

    hql.append(" order by ")
        .append(
            expandSorts(sort).stream().map(this::orderFragment).collect(Collectors.joining(", ")));

    TypedQuery<String> query = entityManager.createQuery(hql.toString(), String.class);
    params.forEach(query::setParameter);
    return query.getResultList().stream().map(String::trim).toList();
  }

  // ---------------------------------------------------------------------------------------------
  // Mutations
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional
  public Node createNewNode(
      String nodeId,
      String creatorId,
      String ownerId,
      String parentId,
      String name,
      String description,
      NodeType type,
      String ancestorIds,
      Long size) {
    long now = System.currentTimeMillis();
    Node node =
        new Node(
            nodeId,
            creatorId,
            ownerId,
            parentId,
            now,
            now,
            name,
            description,
            type,
            ancestorIds,
            size);
    entityManager.persist(node);
    entityManager.flush();
    // A freshly-constructed entity never had its associations materialised: the EAGER
    // mCustomAttributes @OneToMany stays null (persist does not populate it) and the LAZY
    // collections are plain fields, not Hibernate proxies. Legacy Ebean returned managed
    // collections here, so callers that map the returned Node straight into the GraphQL response
    // (createFolder / copyFolder / copyFile) NPE on node.getCustomAttributes().stream(). Reloading
    // the just-persisted row rebinds every association to a proper (empty) managed/lazy collection.
    entityManager.refresh(node);
    return node;
  }

  @Override
  @Transactional
  public Node updateNode(Node node) {
    node.setUpdatedAt(System.currentTimeMillis());
    return entityManager.merge(node);
  }

  @Override
  @Transactional
  public boolean deleteNode(String nodeId) {
    Node node = entityManager.find(Node.class, normalizeId(nodeId));
    if (node == null) {
      return false;
    }
    entityManager.remove(node);
    return true;
  }

  @Override
  @Transactional
  public int deleteNodes(List<String> nodesIds) {
    if (nodesIds.isEmpty()) {
      return 0;
    }
    // Fetch-then-remove: a JPQL bulk delete would bypass the L1 cache leaving stale managed
    // instances; removing managed entities keeps the persistence context consistent (and lets the
    // DB-level ON DELETE CASCADE clean child tables).
    List<Node> nodes =
        entityManager
            .createQuery("select n from Node n where n.mId in :ids", Node.class)
            .setParameter("ids", nodesIds.stream().map(NodeRepositoryImpl::normalizeId).toList())
            .getResultList();
    nodes.forEach(entityManager::remove);
    return nodes.size();
  }

  @Override
  @Transactional
  public int moveNodes(List<String> nodesIds, Node destinationFolder) {
    if (nodesIds.isEmpty()) {
      return 0;
    }
    String ancestorIds =
        NodeType.ROOT.equals(destinationFolder.getNodeType())
            ? destinationFolder.getId()
            : destinationFolder.getAncestorIds()
                + Node.ANCESTORS_SEPARATOR
                + destinationFolder.getId();
    long now = System.currentTimeMillis();

    // Fetch-then-set (not a JPQL bulk update) so the managed entities stay coherent with the L1
    // cache; Hibernate dirty-checking flushes the changes.
    List<Node> nodes =
        entityManager
            .createQuery("select n from Node n where n.mId in :ids", Node.class)
            .setParameter("ids", nodesIds.stream().map(NodeRepositoryImpl::normalizeId).toList())
            .getResultList();
    nodes.forEach(
        n ->
            n.setParentId(destinationFolder.getId()).setAncestorIds(ancestorIds).setUpdatedAt(now));
    return nodes.size();
  }

  @Override
  @Transactional
  public void invertHiddenFlagNodes(List<Node> nodesToFlag) {
    nodesToFlag.forEach(node -> entityManager.merge(node.setHidden(!node.isHidden())));
  }

  // ---------------------------------------------------------------------------------------------
  // Flag (custom attributes)
  // ---------------------------------------------------------------------------------------------

  @Override
  @Transactional
  public void flagForUser(String nodeId, String userId, boolean flag) {
    getCustomAttributes(nodeId, userId)
        .ifPresentOrElse(
            attributes -> attributes.setFlag(flag),
            () ->
                entityManager.persist(new NodeCustomAttributes(normalizeId(nodeId), userId, flag)));
  }

  private Optional<NodeCustomAttributes> getCustomAttributes(String nodeId, String userId) {
    return entityManager
        .createQuery(
            "select ca from NodeCustomAttributes ca where ca.mCompositeId.mNodeId = :nodeId and"
                + " ca.mCompositeId.mUserId = :userId",
            NodeCustomAttributes.class)
        .setParameter("nodeId", normalizeId(nodeId))
        .setParameter("userId", userId)
        .getResultList()
        .stream()
        .findFirst();
  }

  // ---------------------------------------------------------------------------------------------
  // Trash
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<TrashedNode> getTrashedNode(String nodeId) {
    return Optional.ofNullable(entityManager.find(TrashedNode.class, normalizeId(nodeId)));
  }

  @Override
  public List<String> getTrashedNodeIdsByOldParent(String oldParentId) {
    return entityManager
        .createQuery(
            "select tn.mNodeId from TrashedNode tn where tn.mOldParentId = :parent", String.class)
        .setParameter("parent", normalizeId(oldParentId))
        .getResultList()
        .stream()
        .map(String::trim)
        .toList();
  }

  @Override
  @Transactional
  public void trashNode(String nodeId, String parentId) {
    entityManager.persist(new TrashedNode(normalizeId(nodeId), normalizeId(parentId)));
  }

  @Override
  @Transactional
  public void restoreNode(String nodeId) {
    TrashedNode trashedNode = entityManager.find(TrashedNode.class, normalizeId(nodeId));
    if (trashedNode != null) {
      entityManager.remove(trashedNode);
    }
  }

  @Override
  public List<Node> getAllTrashedNodes(Long retentionTimestamp) {
    return entityManager
        .createQuery(
            "select n from Node n where n.mAncestorIds like :trash and n.mUpdatedAt < :ts",
            Node.class)
        .setParameter("trash", "%" + RootId.TRASH_ROOT + "%")
        .setParameter("ts", retentionTimestamp)
        .getResultList();
  }

  // ---------------------------------------------------------------------------------------------
  // Folder size (native recursive)
  // ---------------------------------------------------------------------------------------------

  @Override
  public Optional<Long> calculateAbsoluteFolderSize(String folderId) {
    Optional<Node> folder = getNode(folderId);
    if (folder.isEmpty() || folder.get().getNodeType() != NodeType.FOLDER) {
      throw new RuntimeException("Node is not a folder or does not exists");
    }

    // Full subtree walked recursively by folder_id; hidden files (and folders/roots) are excluded
    // from the sum, matching the legacy absolute-size semantics.
    String sql =
        """
        WITH RECURSIVE subtree AS (
            SELECT node_id, folder_id FROM node WHERE node_id = :folderId
            UNION ALL
            SELECT n.node_id, n.folder_id
            FROM node n
            INNER JOIN subtree s ON n.folder_id = s.node_id
        )
        SELECT COALESCE(SUM(n.size), 0)
        FROM node n
        INNER JOIN subtree s ON n.node_id = s.node_id
        WHERE n.node_type NOT IN ('FOLDER', 'ROOT')
          AND n.hidden = false
        """;

    Object result =
        entityManager
            .createNativeQuery(sql)
            .setParameter("folderId", normalizeId(folderId))
            .getSingleResult();
    return Optional.of(((Number) result).longValue());
  }

  @Override
  public Optional<Long> calculateRelativeFolderSize(String folderId, String userId) {
    Optional<Node> folder = getNode(folderId);
    if (folder.isEmpty() || folder.get().getNodeType() != NodeType.FOLDER) {
      return Optional.empty();
    }

    // Recursive descent by folder_id where EVERY level must be visible to the requester (owner or
    // shared with rights >= READ). A node whose intermediate ancestor is invisible is pruned even
    // if the node itself would be visible, so the size reflects exactly what the user can browse.
    String sql =
        """
        WITH RECURSIVE visible_hierarchy AS (
            SELECT n.node_id, n.folder_id, n.node_type, 0::BIGINT AS size
            FROM node n
            WHERE n.node_id = :folderId
              AND n.node_type = 'FOLDER'
              AND (
                  n.owner_id = :userId
                  OR EXISTS (
                      SELECT 1 FROM share s
                      WHERE s.node_id = n.node_id
                        AND s.target_uuid = :userId
                        AND s.rights >= :read
                  )
              )
            UNION ALL
            SELECT n.node_id, n.folder_id, n.node_type,
                   CASE WHEN n.node_type IN ('FOLDER', 'ROOT') THEN 0 ELSE COALESCE(n.size, 0) END
            FROM node n
            INNER JOIN visible_hierarchy vh ON n.folder_id = vh.node_id
            WHERE (
                  n.owner_id = :userId
                  OR EXISTS (
                      SELECT 1 FROM share s
                      WHERE s.node_id = n.node_id
                        AND s.target_uuid = :userId
                        AND s.rights >= :read
                  )
              )
        )
        SELECT COALESCE(SUM(size), 0)
        FROM visible_hierarchy
        WHERE node_type NOT IN ('FOLDER', 'ROOT')
        """;

    try {
      Object result =
          entityManager
              .createNativeQuery(sql)
              .setHint("jakarta.persistence.query.timeout", 30000)
              .setParameter("folderId", normalizeId(folderId))
              .setParameter("userId", userId)
              .setParameter("read", ACL.READ)
              .getSingleResult();
      return Optional.of(((Number) result).longValue());
    } catch (RuntimeException e) {
      logger.error(
          "Error calculating relative folder size for folder {} and user {}: {}",
          folderId,
          userId,
          e.getMessage(),
          e);
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // findNodes + keyset pagination
  // ---------------------------------------------------------------------------------------------

  @Override
  public ImmutablePair<List<Node>, String> findNodes(
      String userId,
      Optional<NodeSort> sort,
      Optional<Boolean> flagged,
      Optional<String> folderId,
      Optional<Boolean> cascade,
      Optional<Boolean> sharedWithMe,
      Optional<Boolean> sharedByMe,
      Optional<Boolean> directShare,
      Optional<Integer> limit,
      Optional<NodeType> optNodeType,
      Optional<String> optOwnerId,
      List<String> keywords,
      Optional<String> pageToken) {

    if (pageToken.isPresent()) {
      PageToken token = decodeToken(pageToken.get());
      // Clamp exactly like the non-token path below: a page token is client-supplied data (even
      // once signed, the CONTENT is still whatever the server itself put there on a previous
      // page), and the cap must hold on every page of a paginated search, not just the first.
      int tokenLimit =
          token.limit != null ? Math.min(token.limit, Pagination.LIMIT) : Pagination.LIMIT;
      Optional<NodeSort> tokenSort = Optional.ofNullable(token.sort).map(NodeSort::valueOf);
      List<NodeSort> realSorts = getRealSortingsToApply(tokenSort);
      List<Node> nodes =
          doFind(
              userId,
              tokenLimit,
              realSorts,
              Optional.ofNullable(token.flagged),
              Optional.ofNullable(token.folderId),
              Optional.ofNullable(token.cascade),
              Optional.ofNullable(token.sharedWithMe),
              Optional.ofNullable(token.sharedByMe),
              Optional.ofNullable(token.directShare),
              Optional.ofNullable(token.nodeType).map(NodeType::valueOf),
              Optional.ofNullable(token.ownerId),
              token.keywords == null ? List.of() : token.keywords,
              Optional.of(token.cursor));
      return withNextToken(
          nodes,
          tokenLimit,
          tokenSort,
          Optional.ofNullable(token.flagged),
          Optional.ofNullable(token.folderId),
          Optional.ofNullable(token.cascade),
          Optional.ofNullable(token.sharedWithMe),
          Optional.ofNullable(token.sharedByMe),
          Optional.ofNullable(token.directShare),
          Optional.ofNullable(token.nodeType).map(NodeType::valueOf),
          Optional.ofNullable(token.ownerId),
          token.keywords == null ? List.of() : token.keywords);
    }

    int realLimit = limit.map(l -> Math.min(l, Pagination.LIMIT)).orElse(Pagination.LIMIT);
    List<NodeSort> realSorts = getRealSortingsToApply(sort);
    List<Node> nodes =
        doFind(
            userId,
            realLimit,
            realSorts,
            flagged,
            folderId,
            cascade,
            sharedWithMe,
            sharedByMe,
            directShare,
            optNodeType,
            optOwnerId,
            keywords,
            Optional.empty());
    return withNextToken(
        nodes,
        realLimit,
        sort,
        flagged,
        folderId,
        cascade,
        sharedWithMe,
        sharedByMe,
        directShare,
        optNodeType,
        optOwnerId,
        keywords);
  }

  @Override
  public String createPageToken(
      Node node,
      Integer limit,
      Optional<NodeSort> sort,
      Optional<Boolean> flagged,
      Optional<String> folderId,
      Optional<Boolean> cascade,
      Optional<Boolean> sharedWithMe,
      Optional<Boolean> sharedByMe,
      Optional<Boolean> directShare,
      Optional<NodeType> optNodeType,
      Optional<String> optOwnerId,
      List<String> keywords)
      throws JsonProcessingException {
    PageToken token = new PageToken();
    token.limit = limit;
    sort.ifPresent(s -> token.sort = s.name());
    flagged.ifPresent(f -> token.flagged = f);
    folderId.ifPresent(f -> token.folderId = f);
    cascade.ifPresent(c -> token.cascade = c);
    sharedWithMe.ifPresent(s -> token.sharedWithMe = s);
    sharedByMe.ifPresent(s -> token.sharedByMe = s);
    directShare.ifPresent(d -> token.directShare = d);
    optNodeType.ifPresent(t -> token.nodeType = t.name());
    optOwnerId.ifPresent(o -> token.ownerId = o);
    token.keywords = keywords == null ? new ArrayList<>() : new ArrayList<>(keywords);

    List<Object> cursor = new ArrayList<>();
    for (NodeSort s : getRealSortingsToApply(sort)) {
      cursor.add(cursorValue(node, s));
    }
    token.cursor = cursor;

    return encodeToken(token);
  }

  @Override
  public ImmutablePair<List<Node>, String> publicFindNodes(
      String folderId, @Nullable Integer limit, @Nullable String pageToken) {
    int realLimit = (limit != null && limit < Pagination.LIMIT) ? limit : Pagination.LIMIT;

    // Public browsing has NO visibility filter: it lists the direct children of the (public)
    // folder,
    // ordered category-then-name-then-id, and keeps the same keyset-token machinery.
    List<NodeSort> realSorts = List.of(NodeSort.TYPE_ASC, NodeSort.NAME_ASC, NodeSort.ID_ASC);
    int pageLimit;
    Optional<List<Object>> cursor;
    if (pageToken != null) {
      PageToken token = decodeToken(pageToken);
      // The caller (PublicNodeDataFetchers#findNodes) has ALREADY validated `folderId` against the
      // public link and its access code before this method is ever invoked. Scoping MUST use that
      // validated argument, never a value carried inside the token: the token is round-tripped
      // through the client, so trusting a folderId out of it for the actual DB scope (as the
      // pre-fix code did, falling back to the shared RootId.LOCAL_ROOT when absent) let a token
      // dictate what subtree gets queried, independent of which link/folder was authorized for
      // THIS request. If the token still carries a folderId, it must match the validated argument
      // exactly, or it is either stale (minted while browsing a different, unrelated link) or
      // tampered — reject outright rather than silently ignoring the mismatch.
      if (token.folderId != null && !token.folderId.trim().equals(folderId.trim())) {
        throw new IllegalArgumentException("Page token does not match the requested folder");
      }
      pageLimit = token.limit != null ? Math.min(token.limit, Pagination.LIMIT) : Pagination.LIMIT;
      cursor = Optional.of(token.cursor);
    } else {
      pageLimit = realLimit;
      cursor = Optional.empty();
    }

    Map<String, Object> params = new HashMap<>();
    StringBuilder hql = new StringBuilder("select n from Node n where n.mParentId = :parentId");
    params.put("parentId", normalizeId(folderId));
    cursor.ifPresent(c -> hql.append(" and ").append(keysetPredicate(realSorts, c, params)));
    hql.append(" order by ")
        .append(realSorts.stream().map(this::orderFragment).collect(Collectors.joining(", ")));

    TypedQuery<Node> query = entityManager.createQuery(hql.toString(), Node.class);
    params.forEach(query::setParameter);
    query.setMaxResults(pageLimit);

    String trimmedFolderId = folderId.trim();
    List<Node> nodes =
        query.getResultList().stream()
            // Defensive: only nodes that actually descend from the requested (validated) public
            // folder — the ARGUMENT, matching the legacy belt-and-braces check.
            .filter(node -> node.getAncestorsList().contains(trimmedFolderId))
            .toList();

    if (nodes.size() == pageLimit) {
      try {
        String nextToken =
            createPageToken(
                nodes.get(nodes.size() - 1),
                pageLimit,
                Optional.of(NodeSort.NAME_ASC),
                Optional.empty(),
                Optional.of(folderId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
        return new ImmutablePair<>(nodes, nextToken);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Unable to serialize page token", e);
      }
    }
    return new ImmutablePair<>(nodes, null);
  }

  /**
   * Executes a single page of the find query. The visibility rule (owner OR shared-to-me, not a
   * root, not hidden) is always applied; every other criterion is added on top with {@code AND},
   * mirroring the legacy behaviour.
   */
  private List<Node> doFind(
      String userId,
      int limit,
      List<NodeSort> realSorts,
      Optional<Boolean> flagged,
      Optional<String> folderId,
      Optional<Boolean> cascade,
      Optional<Boolean> sharedWithMe,
      Optional<Boolean> sharedByMe,
      Optional<Boolean> directShare,
      Optional<NodeType> optNodeType,
      Optional<String> optOwnerId,
      List<String> keywords,
      Optional<List<Object>> cursor) {

    Map<String, Object> params = new HashMap<>();
    params.put("userId", userId);

    StringBuilder where =
        new StringBuilder(
            "(n.mOwnerId = :userId or exists (select 1 from Share sh where"
                + " sh.composedPrimaryKey.mNodeId = n.mId and sh.composedPrimaryKey.mTargetUserId ="
                + " :userId)) and n.mNodeCategory <> 0 and n.mHidden = false");

    for (int i = 0; i < keywords.size(); i++) {
      where
          .append(" and (lower(n.mName) like :kw")
          .append(i)
          .append(" escape '!'")
          .append(" or lower(n.mDescription) like :kw")
          .append(i)
          .append(" escape '!')");
      params.put("kw" + i, "%" + escapeLike(keywords.get(i).toLowerCase()) + "%");
    }

    flagged.ifPresent(
        f -> {
          if (f) {
            where.append(
                " and exists (select 1 from NodeCustomAttributes ca where ca.mCompositeId.mNodeId ="
                    + " n.mId and ca.mCompositeId.mUserId = :userId and ca.mFlag = true)");
          } else {
            where.append(
                " and (exists (select 1 from NodeCustomAttributes ca where ca.mCompositeId.mNodeId"
                    + " = n.mId and ca.mCompositeId.mUserId = :userId and ca.mFlag = false) or not"
                    + " exists (select 1 from NodeCustomAttributes ca2 where"
                    + " ca2.mCompositeId.mNodeId = n.mId))");
          }
        });

    folderId.ifPresent(
        fid -> {
          if (cascade.orElse(true)) {
            where.append(" and n.mAncestorIds like :ancestorLike");
            params.put("ancestorLike", "%" + fid.trim() + "%");
          } else {
            where.append(" and n.mParentId = :parentId");
            params.put("parentId", normalizeId(fid));
          }
        });

    sharedWithMe.ifPresent(
        swm -> {
          if (swm) {
            where.append(
                " and exists (select 1 from Share s2 where s2.composedPrimaryKey.mNodeId = n.mId"
                    + " and s2.composedPrimaryKey.mTargetUserId = :userId)");
          } else {
            where.append(" and n.mOwnerId = :userId");
          }
        });

    sharedByMe.ifPresent(
        sbm -> {
          where.append(" and n.mOwnerId = :userId");
          String subquery =
              "exists (select 1 from Share s3 where s3.composedPrimaryKey.mNodeId = n.mId and"
                  + " s3.permissions is not null)";
          where.append(" and ").append(sbm ? subquery : "not " + subquery);
        });

    directShare.ifPresent(
        direct -> {
          where.append(
              " and exists (select 1 from Share s4 where s4.composedPrimaryKey.mNodeId = n.mId and"
                  + " s4.direct = :directShare)");
          params.put("directShare", direct);
        });

    optNodeType.ifPresent(
        type -> {
          where.append(" and n.mNodeType = :nodeType");
          params.put("nodeType", type);
        });

    optOwnerId.ifPresent(
        owner -> {
          where.append(" and n.mOwnerId = :ownerId");
          params.put("ownerId", owner);
        });

    cursor.ifPresent(c -> where.append(" and ").append(keysetPredicate(realSorts, c, params)));

    String orderBy = realSorts.stream().map(this::orderFragment).collect(Collectors.joining(", "));

    TypedQuery<Node> query =
        entityManager.createQuery(
            "select n from Node n where " + where + " order by " + orderBy, Node.class);
    params.forEach(query::setParameter);
    query.setMaxResults(limit);
    return query.getResultList();
  }

  private ImmutablePair<List<Node>, String> withNextToken(
      List<Node> nodes,
      Integer limit,
      Optional<NodeSort> sort,
      Optional<Boolean> flagged,
      Optional<String> folderId,
      Optional<Boolean> cascade,
      Optional<Boolean> sharedWithMe,
      Optional<Boolean> sharedByMe,
      Optional<Boolean> directShare,
      Optional<NodeType> optNodeType,
      Optional<String> optOwnerId,
      List<String> keywords) {
    // A full page means there might be more rows: hand back a cursor to the last one.
    if (nodes.size() == limit) {
      try {
        String next =
            createPageToken(
                nodes.get(nodes.size() - 1),
                limit,
                sort,
                flagged,
                folderId,
                cascade,
                sharedWithMe,
                sharedByMe,
                directShare,
                optNodeType,
                optOwnerId,
                keywords);
        return new ImmutablePair<>(nodes, next);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Unable to serialize page token", e);
      }
    }
    return new ImmutablePair<>(nodes, null);
  }

  /**
   * Builds the lexicographic keyset predicate {@code (c0,c1,...,cn) > (v0,v1,...,vn)} honouring
   * each column's sort direction, e.g. for [cat ASC, name ASC, id ASC]:
   *
   * <pre>
   * (cat &gt; :ks0) or (cat = :ks0 and name &gt; :ks1) or (cat = :ks0 and name = :ks1 and id &gt; :ks2)
   * </pre>
   *
   * The cursor values are bound into {@code params} under {@code ks0..ksN}.
   *
   * <p>Every term uses {@link #collatedFieldPath}, NOT the plain {@link #fieldPath} — the same
   * expression {@link #orderFragment} puts in the {@code ORDER BY}. A collated column compared with
   * a plain (default-collation) predicate would walk a DIFFERENT total order than the one {@code
   * ORDER BY} actually produced, silently skipping or repeating rows across a page boundary;
   * keeping both call sites funnelled through the identical helper is what rules that out.
   */
  private String keysetPredicate(
      List<NodeSort> sorts, List<Object> cursor, Map<String, Object> params) {
    List<String> orParts = new ArrayList<>();
    for (int j = 0; j < sorts.size(); j++) {
      List<String> andParts = new ArrayList<>();
      for (int i = 0; i < j; i++) {
        andParts.add(collatedFieldPath(sorts.get(i)) + " = :ks" + i);
      }
      String op = sorts.get(j).getOrder() == SortOrder.ASCENDING ? ">" : "<";
      andParts.add(collatedFieldPath(sorts.get(j)) + " " + op + " :ks" + j);
      orParts.add("(" + String.join(" and ", andParts) + ")");
    }
    for (int i = 0; i < sorts.size(); i++) {
      params.put("ks" + i, cursor.get(i));
    }
    return "(" + String.join(" or ", orParts) + ")";
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Decides the full ordered list of sorts to apply: type first (folders before files), then the
   * requested sort (SIZE additionally breaks ties by name), and always {@code node_id} last so the
   * ordering is a strict total order (required for stable keyset pagination).
   */
  static List<NodeSort> getRealSortingsToApply(Optional<NodeSort> inputSort) {
    List<NodeSort> result = expandSorts(inputSort);
    result.add(NodeSort.ID_ASC);
    return result;
  }

  private static List<NodeSort> expandSorts(Optional<NodeSort> inputSort) {
    List<NodeSort> result = new ArrayList<>();
    inputSort.ifPresentOrElse(
        s -> {
          if (s == NodeSort.SIZE_ASC) {
            result.add(NodeSort.TYPE_ASC);
            result.add(s);
            result.add(NodeSort.NAME_ASC);
          } else if (s == NodeSort.SIZE_DESC) {
            result.add(NodeSort.TYPE_DESC);
            result.add(s);
            result.add(NodeSort.NAME_ASC);
          } else {
            result.add(NodeSort.TYPE_ASC);
            result.add(s);
          }
        },
        () -> result.add(NodeSort.TYPE_ASC));
    return result;
  }

  /** Entity field path (prefixed with the {@code n} alias) for a sort's DB column. */
  private static String fieldPath(NodeSort sort) {
    return "n." + FIELD_BY_COLUMN.get(sort.getName());
  }

  /**
   * Whether {@code sort}'s column is one where the resolved database collation actually changes
   * comparison/ordering semantics. Mirrors the legacy {@code NodeSort#getOrderEbeanQuery} per-value
   * behaviour exactly: {@code NAME}/{@code OWNER}/{@code LAST_EDITOR} (all free-text {@code
   * VARCHAR} identity columns) honoured a supplied collate; {@code ID} (always a fixed-format UUID,
   * and never itself collation-ambiguous), {@code TYPE} (an integer category), and the
   * timestamp/size numeric columns ignored it.
   */
  private static boolean isCollatable(NodeSort sort) {
    return switch (sort) {
      case NAME_ASC, NAME_DESC, OWNER_ASC, OWNER_DESC, LAST_EDITOR_ASC, LAST_EDITOR_DESC -> true;
      case ID_ASC,
          TYPE_ASC,
          TYPE_DESC,
          UPDATED_AT_ASC,
          UPDATED_AT_DESC,
          CREATED_AT_ASC,
          CREATED_AT_DESC,
          SIZE_ASC,
          SIZE_DESC ->
          false;
    };
  }

  /**
   * {@link #fieldPath}, wrapped in Hibernate's HQL {@code collate(x as name)} function when (a)
   * {@link #isCollatable} and (b) {@link CollationRepository} resolved a non-default collation to
   * apply (i.e. the database's own default collation is the locale-less {@code C}/{@code C.UTF-8} —
   * see {@link CollationRepositoryImpl}). Used for BOTH the {@code ORDER BY} term ({@link
   * #orderFragment}) and the matching keyset cursor comparison ({@link #keysetPredicate}) — see
   * that method's javadoc for why they must render the identical expression.
   *
   * <p>{@link CollationRepository} returns the raw-SQL, already-double-quoted identifier form
   * (legacy's contract, e.g. {@code "en_US.utf8"}, ready to drop into a native query). Hibernate's
   * HQL {@code collate(x as name)} function instead parses {@code name} as an identifier and
   * expects it backtick-quoted when (as here) it contains characters — the dot — that are not legal
   * in a bare HQL identifier; Hibernate then re-quotes it correctly for the target dialect ("Some
   * PostgreSQL collation names may require quoting with backticks" — Hibernate ORM reference docs).
   * So the outer double quotes {@link CollationRepository} adds for its raw-SQL contract are
   * stripped here and the bare name is re-wrapped in backticks instead.
   */
  private String collatedFieldPath(NodeSort sort) {
    String field = fieldPath(sort);
    if (!isCollatable(sort)) {
      return field;
    }
    return collationRepository
        .getValidCollateForQuery()
        .map(
            quoted ->
                "collate(" + field + " as `" + quoted.substring(1, quoted.length() - 1) + "`)")
        .orElse(field);
  }

  private String orderFragment(NodeSort sort) {
    return collatedFieldPath(sort) + (sort.getOrder() == SortOrder.ASCENDING ? " asc" : " desc");
  }

  /**
   * Extracts the value of a node for the column a given sort orders by (the keyset cursor value).
   */
  private static Object cursorValue(Node node, NodeSort sort) {
    return switch (sort) {
      case ID_ASC -> node.getId();
      case NAME_ASC, NAME_DESC -> node.getFullName();
      case TYPE_ASC, TYPE_DESC -> (int) node.getNodeCategory().getValue();
      case SIZE_ASC, SIZE_DESC -> node.getSize();
      case CREATED_AT_ASC, CREATED_AT_DESC -> node.getCreatedAt();
      case UPDATED_AT_ASC, UPDATED_AT_DESC -> node.getUpdatedAt();
      case OWNER_ASC, OWNER_DESC -> node.getOwnerId();
      case LAST_EDITOR_ASC, LAST_EDITOR_DESC -> node.getLastEditorId().orElse(null);
    };
  }

  /** Pads an id to the fixed {@code CHARACTER(36)} width so a bound parameter matches storage. */
  private static String normalizeId(String id) {
    if (id == null || id.length() >= 36) {
      return id;
    }
    return id + " ".repeat(36 - id.length());
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  /**
   * Serialises the token AND signs it: an HMAC-SHA256 over the JSON of every field EXCEPT {@link
   * PageToken#signature} itself (excluded via {@link PageTokenSignatureMixIn}), keyed by {@link
   * FilesConfig#getPageTokenSecretKey()}. Ports legacy {@code PageQuery#toToken} faithfully onto
   * this class's field-based (not getter-based) shape.
   */
  private String encodeToken(PageToken token) throws JsonProcessingException {
    token.signature = computeSignature(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(token));
  }

  /**
   * Decodes AND verifies a page token, distinguishing two failure modes that legacy also kept
   * separate (its {@code fromToken} threw a different message for a deserialisation failure than
   * for a signature mismatch): a token that fails to Base64/JSON-decode is MALFORMED — no signature
   * was ever checked, so the message must not claim one was; a token that decodes fine but whose
   * signature does not match the recomputed one is a genuine {@code InvalidTokenSignature} failure
   * — a tampered, forged, or replayed-under-a-stale-key token. Both are rejected, but conflating
   * the messages (as the pre-fix code did, labelling every failure "Invalid token signature") is
   * exactly the kind of misleading signal that let this vulnerability go unnoticed: a reader would
   * reasonably assume a signature was actually being verified.
   */
  private PageToken decodeToken(String token) {
    PageToken pageToken;
    try {
      pageToken = MAPPER.readValue(Base64.getUrlDecoder().decode(token), PageToken.class);
    } catch (IllegalArgumentException | IOException e) {
      throw new IllegalArgumentException("Malformed page token", e);
    }
    String expectedSignature;
    try {
      expectedSignature = computeSignature(pageToken);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Malformed page token", e);
    }
    byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.UTF_8);
    byte[] actualBytes =
        (pageToken.signature == null ? "" : pageToken.signature).getBytes(StandardCharsets.UTF_8);
    if (pageToken.signature == null || !MessageDigest.isEqual(expectedBytes, actualBytes)) {
      throw new IllegalArgumentException("Invalid page token signature");
    }
    return pageToken;
  }

  /**
   * HMAC-SHA256 over the token's fields (excluding {@link PageToken#signature}), Base64-encoded.
   */
  private String computeSignature(PageToken token) throws JsonProcessingException {
    ObjectMapper signingMapper = new ObjectMapper();
    signingMapper.addMixIn(PageToken.class, PageTokenSignatureMixIn.class);
    String dataToSign = signingMapper.writeValueAsString(token);
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(
          new SecretKeySpec(
              filesConfig.getPageTokenSecretKey().getBytes(StandardCharsets.UTF_8),
              HMAC_ALGORITHM));
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(dataToSign.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Unable to compute page token signature", e);
    }
  }

  /**
   * Jackson mix-in excluding {@link PageToken#signature} from the bytes that get signed. Must be
   * registered for reflection: in a native image the {@code @JsonIgnore} is otherwise dropped, so
   * signing includes {@code "signature":null} while verification includes the real signature —
   * every freshly minted page token then fails its own signature check.
   */
  @RegisterForReflection
  private abstract static class PageTokenSignatureMixIn {
    @JsonIgnore public String signature;
  }

  /**
   * Opaque, JSON-serialised page cursor. Carries the full search criteria (so the next page re-runs
   * the identical query) plus {@link #cursor}: the last returned row's value for every applied sort
   * column, in {@link NodeRepositoryImpl#getRealSortingsToApply} order. {@link #signature} is an
   * HMAC-SHA256 (see {@link #computeSignature}) over every OTHER field, verified on decode so the
   * token is tamper-evident — restoring the property the legacy {@code PageQuery} had and the
   * Quarkus port had silently dropped.
   */
  public static final class PageToken {
    public String signature;
    public Integer limit;
    public String sort;
    public Boolean flagged;
    public String folderId;
    public Boolean cascade;
    public Boolean sharedWithMe;
    public Boolean sharedByMe;
    public Boolean directShare;
    public String nodeType;
    public String ownerId;
    public List<String> keywords = new ArrayList<>();
    public List<Object> cursor = new ArrayList<>();

    public PageToken() {}
  }
}
