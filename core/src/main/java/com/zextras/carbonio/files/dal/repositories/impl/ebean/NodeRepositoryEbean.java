// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Config.Pagination;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.Files.Db.RootId;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FindNodeKeySetBuilder;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.PageQuery;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SearchBuilder;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.ebean.Query;
import io.ebean.Transaction;
import io.ebean.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NodeRepositoryEbean implements NodeRepository {

  private static final Logger logger = LoggerFactory.getLogger(NodeRepositoryEbean.class);

  private EbeanDatabaseManager mDB;

  @Inject
  public NodeRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager) {
    mDB = ebeanDatabaseManager;
  }

  /**
   * This method creates a new pageToken based on the find params and the data of a node, used to
   * creating the cursor to the nextPage
   *
   * @param node a {@link Node} that MUST be the last node of the previous page
   * @param limit the number of nodes to retrieve
   * @param sort the sort used for ordering the dataset
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

    return nextPage.toToken();
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
        .lt(Files.Db.Node.UPDATED_AT, retentionTimestamp)
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
      Optional<String> keyset,
      Optional<NodeType> optNodeType,
      Optional<String> optOwnerId) {

    SearchBuilder search = new SearchBuilder(mDB.getEbeanDatabase(), userId);

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
    keyset.ifPresent(search::setKeyset);

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
   * keyset generation for pagination.
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
              PageQuery params = PageQuery.fromToken(token);
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
                              (l >= Files.Config.Pagination.LIMIT)
                                  ? Files.Config.Pagination.LIMIT
                                  : l)
                      .orElse(Files.Config.Pagination.LIMIT);

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
            .map(PageQuery::fromToken)
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
      findNodeQuery.where().and().raw(pageQuery.getKeySet().get());
    }

    List<Node> nodes =
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
   * @param sort the sorting for the list of nodes
   * @return
   */
  private List<Node> getRealNodes(List<String> nodeIds, Optional<NodeSort> sort) {
    Query<Node> query =
        mDB.getEbeanDatabase().createQuery(Node.class).where().idIn(nodeIds).query();

    sort.map(s -> s.getOrderEbeanQuery(query));
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
            .select(Files.Db.Node.ID)
            .where()
            .eq(Files.Db.Node.PARENT_ID, nodeId)
            .query();

    if (nodeId.equals(RootId.LOCAL_ROOT) && userId.isPresent()) {
      query.where().eq(Db.Node.OWNER_ID, userId.get());
    }

    sort.ifPresentOrElse(
        s -> {
          if (s.equals(NodeSort.SIZE_ASC)) {
            NodeSort.TYPE_ASC.getOrderEbeanQuery(query);
            s.getOrderEbeanQuery(query);
            NodeSort.NAME_ASC.getOrderEbeanQuery(query);
          } else if (s.equals(NodeSort.SIZE_DESC)) {
            NodeSort.TYPE_DESC.getOrderEbeanQuery(query);
            s.getOrderEbeanQuery(query);
            NodeSort.NAME_ASC.getOrderEbeanQuery(query);
          } else {
            NodeSort.TYPE_ASC.getOrderEbeanQuery(query);
            s.getOrderEbeanQuery(query);
          }
        },
        () -> NodeSort.TYPE_ASC.getOrderEbeanQuery(query));

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
    return mDB.getEbeanDatabase().find(Node.class).where().in(Files.Db.Node.ID, nodesIds).delete();
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
   *     otherwise it returns Optional.empty();
   */
  private Optional<NodeCustomAttributes> getCustomAttributesForUser(String nodeId, String userId) {
    return mDB.getEbeanDatabase()
        .find(NodeCustomAttributes.class)
        .where()
        .eq(Files.Db.NodeCustomAttributes.NODE_ID, nodeId)
        .eq(Files.Db.NodeCustomAttributes.USER_ID, userId)
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
        .lt(Files.Db.Node.UPDATED_AT, retentionTimestamp)
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
  public void invertHiddenFlagNodes(List<Node> nodesToFlag) {
    try (Transaction txn = mDB.getEbeanDatabase().beginTransaction()) {
      for (Node node : nodesToFlag) {
        mDB.getEbeanDatabase().update(node.setHidden(!node.isHidden()));
      }
      txn.commit();
    }
  }
}
