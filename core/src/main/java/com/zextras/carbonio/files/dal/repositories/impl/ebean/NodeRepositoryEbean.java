// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Config.Pagination;
import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.*;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.*;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.ebean.Query;
import io.ebean.SqlQuery;
import io.ebean.SqlRow;
import io.ebean.annotation.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class NodeRepositoryEbean implements NodeRepository {

  private static final Logger logger = LoggerFactory.getLogger(NodeRepositoryEbean.class);

  private EbeanDatabaseManager mDB;
  private FilesConfig filesConfig;
  private CollationRepository collationRepository;

  @Inject
  public NodeRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager, FilesConfig filesConfig, CollationRepository collationRepository) {
    mDB = ebeanDatabaseManager;
    this.filesConfig = filesConfig;
    this.collationRepository = collationRepository;
  }

  /**
   * This method creates a new pageToken based on the find params and the data of a node, used to
   * creating the cursor to the nextPage
   *
   * @param node    a {@link Node} that MUST be the last node of the previous page
   * @param limit   the number of nodes to retrieve
   * @param sort    the sort used for ordering the dataset
   * @param flagged the value of the flag
   * @return a {@link String} containing the next pageToken
   */
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
      List<String> keywords) {
    PageQuery nextPage = new PageQuery();
    nextPage.setLimit(limit);
    nextPage.setKeywords(keywords);
    sort.ifPresent(s -> nextPage.setSort(s.name()));
    flagged.ifPresent(nextPage::setFlagged);
    folderId.ifPresent(nextPage::setFolderId);
    cascade.ifPresent(nextPage::setCascade);
    sharedWithMe.ifPresent(nextPage::setSharedWithMe);
    sharedByMe.ifPresent(nextPage::setSharedByMe);
    directShare.ifPresent(nextPage::setDirectShare);
    optNodeType.ifPresent(nextPage::setNodeType);
    optOwnerId.ifPresent(nextPage::setOwnerId);

    List<NodeSort> realSortsToApply = getRealSortingsToApply(sort);
    nextPage.setKeySet(
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSorts(realSortsToApply)
            .fromNode(node)
            .build());
    return nextPage.toToken(filesConfig.getPageTokenSecretKey());
  }

  /**
   * Directly retrieves from DB a node given its id
   *
   * @param nodeId the id of the node to retrieve
   * @return
   */
  private Optional<Node> getRealNode(String nodeId) {
    String normalizedId = nodeId + StringUtils.repeat(" ", 36 - nodeId.length());
    return mDB.getEbeanDatabase().find(Node.class).where().idEq(normalizedId).findOneOrEmpty();
  }

  @Override
  public List<Node> getAllTrashedNodes(Long retentionTimestamp) {

    return mDB.getEbeanDatabase()
        .find(Node.class)
        .where()
        .contains(Db.Node.ANCESTOR_IDS, Db.RootId.TRASH_ROOT)
        .lt(Constants.Db.Node.UPDATED_AT, retentionTimestamp)
        .findList();
  }

  /**
   * Function used as utility to effectively execute the find operation, this is used to avoid
   * duplication since the main function must first extrapolate the params from the pageToken or use
   * the provided ones.
   */
  private List<Node> doFind(
      String userId,
      Integer limit,
      List<NodeSort> sorts,
      Optional<Boolean> flagged,
      Optional<String> folderId,
      Optional<Boolean> cascade,
      Optional<Boolean> sharedWithMe,
      Optional<Boolean> sharedByMe,
      Optional<Boolean> directShare,
      List<String> keywords,
      Optional<SQLExpression> keySet,
      Optional<NodeType> optNodeType,
      Optional<String> optOwnerId) {

    SearchBuilder search = new SearchBuilder(mDB.getEbeanDatabase(), userId, collationRepository.getValidCollateForQuery());

    long startTime = java.lang.System.nanoTime();

    if (!keywords.isEmpty()) {
      search.setKeywords(keywords);
    }
    flagged.ifPresent(search::setFlagged);
    folderId.ifPresent(fId -> search.setFolderId(fId, cascade.orElse(true)));
    sharedWithMe.ifPresent(swm -> search.setSharedWithMe(userId, swm));
    sharedByMe.ifPresent(search::setSharedByMe);
    directShare.ifPresent(search::setDirectShare);
    optNodeType.ifPresent(search::setNodeType);
    optOwnerId.ifPresent(search::setOwner);

    search.setLimit(limit);
    keySet.ifPresent(ks -> search.setKeySet(ks.toExpression(), ks.getParameters().toArray()));

    sorts.forEach(search::setSort);

    List<Node> nodes = search.build().findList();

    long endTime = java.lang.System.nanoTime();
    long totalTime = (endTime - startTime) / 1_000_000;

    logger.info("Search time in milliseconds : " + totalTime);

    return nodes;
  }

  /**
   * This is the single method that decides what sortings to apply and in what order. This is
   * responsible for default sorting, additional sorting not explicitly requested by user, and
   * keySet generation for pagination.
   */
  public static List<NodeSort> getRealSortingsToApply(Optional<NodeSort> inputSort) {
    List<NodeSort> result = new ArrayList<>();
    inputSort.ifPresentOrElse(
        s -> {
          if (s.equals(NodeSort.SIZE_ASC)) {
            result.add(NodeSort.TYPE_ASC);
            result.add(s);
            result.add(NodeSort.NAME_ASC);
          } else if (s.equals(NodeSort.SIZE_DESC)) {
            result.add(NodeSort.TYPE_DESC);
            result.add(s);
            result.add(NodeSort.NAME_ASC);
          } else {
            result.add(NodeSort.TYPE_ASC);
            result.add(s);
          }
        },
        () -> result.add(NodeSort.TYPE_ASC));

    result.add(NodeSort.ID_ASC); // default last sort
    return result;
  }

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

    return pageToken
        .map(
            token -> {
              PageQuery params = PageQuery.fromToken(token, filesConfig.getPageTokenSecretKey());
              List<NodeSort> realSortsToApply =
                  getRealSortingsToApply(params.getSort().map(NodeSort::valueOf));
              List<Node> nodes =
                  doFind(
                      userId,
                      params.getLimit(),
                      realSortsToApply,
                      params.getFlagged(),
                      params.getFolderId(),
                      params.getCascade(),
                      params.getSharedWithMe(),
                      params.getSharedByMe(),
                      params.getDirectShare(),
                      params.getKeywords(),
                      params.getKeySet(),
                      params.getNodeType(),
                      params.getOwnerId());

              if (nodes.size() == params.getLimit()) {
                return new ImmutablePair<>(
                    nodes,
                    createPageToken(
                        nodes.get(nodes.size() - 1),
                        params.getLimit(),
                        params.getSort().map(NodeSort::valueOf),
                        params.getFlagged(),
                        params.getFolderId(),
                        params.getCascade(),
                        params.getSharedWithMe(),
                        params.getSharedByMe(),
                        params.getDirectShare(),
                        params.getNodeType(),
                        params.getOwnerId(),
                        params.getKeywords()));
              } else {
                return new ImmutablePair<List<Node>, String>(nodes, null);
              }
            })
        .orElseGet(
            () -> {
              Integer realLimit =
                  limit
                      .map(
                          l ->
                              (l >= Constants.Config.Pagination.LIMIT)
                                  ? Constants.Config.Pagination.LIMIT
                                  : l)
                      .orElse(Constants.Config.Pagination.LIMIT);

              List<NodeSort> realSortsToApply = getRealSortingsToApply(sort);
              List<Node> nodes =
                  doFind(
                      userId,
                      realLimit,
                      realSortsToApply,
                      flagged,
                      folderId,
                      cascade,
                      sharedWithMe,
                      sharedByMe,
                      directShare,
                      keywords,
                      Optional.empty(),
                      optNodeType,
                      optOwnerId);

              if (nodes.size() == realLimit) {
                return new ImmutablePair<>(
                    nodes,
                    createPageToken(
                        nodes.get(nodes.size() - 1),
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
                        keywords));
              } else {
                return new ImmutablePair<List<Node>, String>(nodes, null);
              }
            });
  }

  public ImmutablePair<List<Node>, String> publicFindNodes(
      String folderId, @Nullable Integer limit, @Nullable String pageToken) {
    int realLimit = limit != null && limit < Pagination.LIMIT ? limit : Pagination.LIMIT;

    PageQuery pageQuery =
        Optional.ofNullable(pageToken)
            .map(token -> PageQuery.fromToken(token, filesConfig.getPageTokenSecretKey()))
            .orElseGet(
                () -> {
                  PageQuery firstPageQuery = new PageQuery();
                  firstPageQuery.setFolderId(folderId);
                  firstPageQuery.setLimit(realLimit);
                  return firstPageQuery;
                });

    Query<Node> findNodeQuery =
        mDB.getEbeanDatabase()
            .find(Node.class)
            .where()
            .eq(Db.Node.PARENT_ID, pageQuery.getFolderId().orElse("LOCAL_ROOT"))
            .query();

    if (pageQuery.getKeySet().isPresent()) {
      SQLExpression keySet = pageQuery.getKeySet().get();
      findNodeQuery.where().and().raw(keySet.toExpression(), keySet.getParameters().toArray());
    }

    List<Node> nodes;
    Optional<String> collation = collationRepository.getValidCollateForQuery();
    if (collation.isPresent()) {
      nodes =
          findNodeQuery
              .orderBy()
              .asc(Db.Node.CATEGORY)
              .orderBy()
              .asc(Db.Node.NAME, collation.get())
              .setMaxRows(pageQuery.getLimit())
              .findList()
              .stream()
              // This filter is tricky because it denies the access of nodes that are not children of
              // the
              // requested public folder
              .filter(node -> node.getAncestorsList().contains(folderId))
              .toList();
    } else {
      nodes =
          findNodeQuery
              .orderBy()
              .asc(Db.Node.CATEGORY)
              .orderBy()
              .asc(Db.Node.NAME)
              .setMaxRows(pageQuery.getLimit())
              .findList()
              .stream()
              // This filter is tricky because it denies the access of nodes that are not children of
              // the
              // requested public folder
              .filter(node -> node.getAncestorsList().contains(folderId))
              .toList();
    }

    if (nodes.size() == realLimit) {
      return new ImmutablePair<>(
          nodes,
          createPageToken(
              nodes.get(nodes.size() - 1),
              realLimit,
              Optional.of(NodeSort.NAME_ASC),
              Optional.empty(),
              Optional.of(folderId),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Collections.emptyList()));
    } else {
      return ImmutablePair.of(nodes, null);
    }
  }

  @Override
  public Optional<Node> getNode(String nodeId) {
    return getRealNode(nodeId);
  }

  /**
   * Directly retrieves from DB a list of nodes
   *
   * @param nodeIds the list of nodes to retrieve
   * @param sort    the sorting for the list of nodes
   * @return
   */
  private List<Node> getRealNodes(List<String> nodeIds, Optional<NodeSort> sort) {
    Query<Node> query =
        mDB.getEbeanDatabase().createQuery(Node.class).where().idIn(nodeIds).query();

    sort.map(s -> s.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery()));
    return query.findList();
  }

  @Override
  public Stream<Node> getNodes(List<String> nodeIds, Optional<NodeSort> sort) {
    return getRealNodes(nodeIds, sort).stream();
  }

  @Override
  public List<String> getChildrenIds(
      String nodeId, Optional<NodeSort> sort, Optional<String> userId, boolean showMarked) {
    Query<Node> query =
        mDB.getEbeanDatabase()
            .createQuery(Node.class)
            .select(Constants.Db.Node.ID)
            .where()
            .eq(Constants.Db.Node.PARENT_ID, nodeId)
            .query();

    if (nodeId.equals(RootId.LOCAL_ROOT) && userId.isPresent()) {
      query.where().eq(Db.Node.OWNER_ID, userId.get());
    }

    sort.ifPresentOrElse(
        s -> {
          if (s.equals(NodeSort.SIZE_ASC)) {
            NodeSort.TYPE_ASC.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
            s.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
            NodeSort.NAME_ASC.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
          } else if (s.equals(NodeSort.SIZE_DESC)) {
            NodeSort.TYPE_DESC.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
            s.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
            NodeSort.NAME_ASC.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
          } else {
            NodeSort.TYPE_ASC.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
            s.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery());
          }
        },
        () -> NodeSort.TYPE_ASC.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery()));

    return query.findIds();
  }

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
    Node node =
        new Node(
            nodeId,
            creatorId,
            ownerId,
            parentId,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            name,
            description,
            type,
            ancestorIds,
            size);
    mDB.getEbeanDatabase().save(node);

    return getNode(nodeId).get();
  }

  @Override
  public boolean deleteNode(String nodeId) {
    return getNode(nodeId).map(node -> mDB.getEbeanDatabase().delete(node)).orElse(false);
  }

  @Override
  public int deleteNodes(List<String> nodesIds) {
    return mDB.getEbeanDatabase().find(Node.class).where().in(Constants.Db.Node.ID, nodesIds).delete();
  }

  @Override
  public void flagForUser(String nodeId, String userId, boolean flag) {
    getCustomAttributesForUser(nodeId, userId)
        .ifPresentOrElse(
            customAttributes -> customAttributes.setFlag(flag),
            () -> {
              NodeCustomAttributes customAttributes =
                  new NodeCustomAttributes(nodeId, userId, flag);
              customAttributes.save();
            });
  }

  @Override
  public boolean isFlaggedForUser(String nodeId, String userId) {
    return getCustomAttributesForUser(nodeId, userId)
        .map(NodeCustomAttributes::getFlag)
        .orElse(false);
  }

  /**
   * Retrieves the customAttributes for the specific node and user.
   *
   * @param nodeId the id of the node to retrieve the custom attributes
   * @param userId the id of the user which to retrieve the custom attributes
   * @return {@link NodeCustomAttributes} if there are custom attributes saved for the user,
   * otherwise it returns Optional.empty();
   */
  private Optional<NodeCustomAttributes> getCustomAttributesForUser(String nodeId, String userId) {
    return mDB.getEbeanDatabase()
        .find(NodeCustomAttributes.class)
        .where()
        .eq(Constants.Db.NodeCustomAttributes.NODE_ID, nodeId)
        .eq(Constants.Db.NodeCustomAttributes.USER_ID, userId)
        .findOneOrEmpty();
  }

  @Override
  public Optional<TrashedNode> getTrashedNode(String nodeId) {
    return mDB.getEbeanDatabase()
        .find(TrashedNode.class)
        .where()
        .eq(Db.Trashed.NODE_ID, nodeId)
        .findOneOrEmpty();
  }

  @Override
  public List<String> getTrashedNodeIdsByOldParent(String oldParentId) {
    return mDB.getEbeanDatabase()
        .find(TrashedNode.class)
        .where()
        .eq(Db.Trashed.PARENT_ID, oldParentId)
        .findIds();
  }

  @Override
  public void trashNode(String nodeId, String parentId) {
    TrashedNode tNode = new TrashedNode(nodeId, parentId);
    mDB.getEbeanDatabase().save(tNode);
  }

  @Override
  public void restoreNode(String nodeId) {
    mDB.getEbeanDatabase().find(TrashedNode.class).where().eq(Db.Trashed.NODE_ID, nodeId).delete();
  }

  @Override
  public Node updateNode(Node node) {
    node.setUpdatedAt(System.currentTimeMillis());
    mDB.getEbeanDatabase().update(node);
    return node;
  }

  @Override
  public int deleteTrashedNodesOlderThan(Long retentionTimestamp) {
    return mDB.getEbeanDatabase()
        .find(Node.class)
        .where()
        .contains(Db.Node.ANCESTOR_IDS, Db.RootId.TRASH_ROOT)
        .lt(Constants.Db.Node.UPDATED_AT, retentionTimestamp)
        .delete();
  }

  @Override
  public int moveNodes(List<String> nodesIds, Node destinationFolder) {
    String ancestorIds =
        NodeType.ROOT.equals(destinationFolder.getNodeType())
            ? destinationFolder.getId()
            : destinationFolder.getAncestorIds() + "," + destinationFolder.getId();

    return mDB.getEbeanDatabase()
        .update(Node.class)
        .set(Db.Node.PARENT_ID, destinationFolder.getId())
        .set(Db.Node.UPDATED_AT, System.currentTimeMillis())
        .set(Db.Node.ANCESTOR_IDS, ancestorIds)
        .where()
        .in(Db.Node.ID, nodesIds)
        .update();
  }

  @Override
  public List<Node> getRootsList() {
    return mDB.getEbeanDatabase().find(Node.class).where().eq(Db.Node.CATEGORY, 0).findList();
  }

  @Override
  public Optional<Node> getNodeByName(String nodeName, String folderId, String nodeOwner) {
    Query<Node> query =
        mDB.getEbeanDatabase()
            .find(Node.class)
            .where()
            .eq(Db.Node.NAME, nodeName)
            .eq(Db.Node.PARENT_ID, folderId)
            .eq(Db.Node.OWNER_ID, nodeOwner)
            .query();

    // We could use query.findOneOrEmpty() but if, for some reason, there are multiple nodes
    // with the same name the query would explode. So to be extra conservative we fetch all the
    // resulting node and then we return the first one
    return query.findList().stream().findFirst();
  }

  @Override
  public List<Node> findNodesByOwner(String ownerId) {
    return mDB.getEbeanDatabase().find(Node.class).where().eq(Db.Node.OWNER_ID, ownerId).findList();
  }

  @Override
  public Optional<Node> findFirstByOwner(String ownerId) {
    return mDB.getEbeanDatabase().find(Node.class).where().eq(Db.Node.OWNER_ID, ownerId).setMaxRows(1).findOneOrEmpty();
  }

  @Override
  @Transactional
  public void invertHiddenFlagNodes(List<Node> nodesToFlag) {
    nodesToFlag.forEach(node -> mDB.getEbeanDatabase().update(node.setHidden(!node.isHidden())));
  }

  @Override
  public List<Node> findAllNodesFiles() {
    return mDB.getEbeanDatabase().find(Node.class).where().ne(Db.Node.TYPE, NodeType.FOLDER).and().ne(Db.Node.TYPE, NodeType.ROOT).findList();
  }

  /*
  Calculates the absolute size of a folder by performing a sum of the sizes of all files that have that folder
  as an ancestor.
   */
  @Override
  public Optional<Long> calculateAbsoluteFolderSize(String folderId) {
    Optional<Node> folderOpt = getNode(folderId);
    if (folderOpt.isEmpty() || folderOpt.get().getNodeType() != NodeType.FOLDER) {
      throw new RuntimeException("Node is not a folder or does not exists");
    }

    Long totalSize = mDB.getEbeanDatabase()
        .find(Node.class)
        .where()
        .contains(Db.Node.ANCESTOR_IDS, folderId)
        .ne(Db.Node.TYPE, NodeType.FOLDER)
        .ne(Db.Node.TYPE, NodeType.ROOT)
        .ne(Db.Node.HIDDEN, true)
        .select("sum(size)::Long")
        .findSingleAttribute();

    return Optional.ofNullable(totalSize);
  }

  /*
  Listen, I'm not proud of this one.
  This abomination of raw SQL calculates the size of a folder relative to a certain user.
  This is necessary when a user has permission to see only some files inside a folder, and we need to know
  what the size will be to him. That means that a folder has an absolute size and a relative size to each user it has
  been shared with. This implies, of course, that if a user is the owner of the folder, absolute size will be equal to
  that user's relative size.
  It works by running a recursive query that explores the hierarchy by the nodes' folder_id (parent folder), checking
  if every node is visible to the requested user (checks hidden node, ownership, permissions).
  This has been necessary because there exists a particular case where, in a hierarchy like folderA(folderB(fileC))), an
  user could have direct shares on folder A and file C but not folder B: this means that even if fileC has folder A as
  an ancestor, and even if file C is visible by the requested user, the size of folder A should not include the size of
  file C, since the user will not actually see C inside A since they can't see B.
   */
  @Override
  public Optional<Long> calculateRelativeFolderSize(String folderId, String userId) {
    Optional<Node> folderOpt = getNode(folderId);
    if (folderOpt.isEmpty() || folderOpt.get().getNodeType() != NodeType.FOLDER) {
      return Optional.empty();
    }

    String sql = """
        WITH RECURSIVE visible_hierarchy AS (
            SELECT 
                n.node_id,
                n.folder_id,
                n.node_type,
                0::BIGINT as size
            FROM node n
            WHERE n.node_id = ?
              AND n.node_type = 'FOLDER'
              AND (
                  n.owner_id = ?
                  OR EXISTS (
                      SELECT 1 
                      FROM share s
                      WHERE s.node_id = n.node_id
                        AND s.target_uuid = ?
                        AND s.rights >= ?
                  )
              )
        
            UNION ALL
        
            SELECT 
                n.node_id,
                n.folder_id,
                n.node_type,
                CASE 
                    WHEN n.node_type IN ('FOLDER', 'ROOT') THEN 0
                    ELSE COALESCE(n.size, 0)
                END as size
            FROM node n
            INNER JOIN visible_hierarchy vh ON n.folder_id = vh.node_id
            WHERE (
                  n.owner_id = ?
                  OR EXISTS (
                      SELECT 1 
                      FROM share s
                      WHERE s.node_id = n.node_id
                        AND s.target_uuid = ?
                        AND s.rights >= ?
                  )
              )
        )
        SELECT COALESCE(SUM(size), 0) as total_size
        FROM visible_hierarchy
        WHERE node_type NOT IN ('FOLDER', 'ROOT')
        """;

    try {
      SqlQuery sqlQuery = mDB.getEbeanDatabase().sqlQuery(sql);

      sqlQuery.setParameter(1, folderId);
      sqlQuery.setParameter(2, userId);
      sqlQuery.setParameter(3, userId);
      sqlQuery.setParameter(4, ACL.READ);
      sqlQuery.setParameter(5, userId);
      sqlQuery.setParameter(6, userId);
      sqlQuery.setParameter(7, ACL.READ);

      sqlQuery.setTimeout(30);

      SqlRow row = sqlQuery.findOne();

      if (row != null) {
        Long totalSize = row.getLong("total_size");
        return Optional.ofNullable(totalSize);
      }

      return Optional.empty();

    } catch (Exception e) {
      logger.error("Error calculating relative folder size for folder {} and user {}: {}",
          folderId, userId, e.getMessage(), e);
      return Optional.empty();
    }
  }
}
