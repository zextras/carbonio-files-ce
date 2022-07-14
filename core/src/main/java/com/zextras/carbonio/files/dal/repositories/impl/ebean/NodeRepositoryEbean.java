// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.Files.Db.RootId;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.PageQuery;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SearchBuilder;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.ebean.Query;
import io.ebean.annotation.Transactional;
import java.text.MessageFormat;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
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
   * This method construct the key set to use for the next page.
   *
   * @param nodeCategory The value of the nodeCategory of the last node of the current page
   * @param nodeId The nodeId of the last node of the current page
   * @param sortField an {@link ImmutableTriple} that must contain: - the name of the ordering field
   * - the direction of the ordering - the value of the ordering field of the last node of the
   * current page
   *
   * @return a {@link String} representing the evaluated key set
   */
  private String buildKeyset(
    Short nodeCategory,
    String nodeId,
    Optional<ImmutableTriple<String, String, String>> sortField
  ) {
    return sortField.map(sField -> {
        return sField.getLeft()
          .equals("size")
          ? "(" + sField.getLeft() + sField.getMiddle() +
          "'" + sField.getRight()
          .replace("'", "''") + "'" + ") " +
          "OR (" + sField.getLeft() + " = " +
          "'" + sField.getRight()
          .replace("'", "''") + "' AND t0.node_id > '" + nodeId + "')"
          : "(node_category > " + nodeCategory + ") " +
            "OR (node_category = " + nodeCategory + " AND " + sField.getLeft() +
            sField.getMiddle() +
            "'" + sField.getRight()
            .replace("'", "''") + "'" + ") " +
            "OR (node_category = " + nodeCategory + " AND " + sField.getLeft() + " = " +
            "'" + sField.getRight()
            .replace("'", "''") + "' AND t0.node_id > '" + nodeId + "')";
      })
      .orElse("(node_category > " + nodeCategory + ") OR " +
        "(node_category = " + nodeCategory + " AND t0.node_id > '" + nodeId + "')");

  }

  /**
   * This method creates a new pageToken based on the find params and the data of a node, used to
   * creating the cursor to the nextPage
   *
   * @param node a {@link Node} that MUST be the last node of the previous page
   * @param limit the number of nodes to retrieve
   * @param sort the sort used for ordering the dataset
   * @param flagged the value of the flag
   *
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
    List<String> keywords
  ) {
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

    sort.ifPresent(s -> {
      switch (s) {
        case NAME_ASC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(new ImmutableTriple(
                MessageFormat.format("LOWER({0})", Db.Node.NAME),
                ">",
                node.getFullName().toLowerCase())
              )
            )
          );
          break;
        case NAME_DESC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(new ImmutableTriple(
                MessageFormat.format("LOWER({0})", Db.Node.NAME),
                "<",
                node.getFullName().toLowerCase())
              )
            )
          );
          break;
        case UPDATED_AT_ASC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(
                new ImmutableTriple(Db.Node.UPDATED_AT, ">", String.valueOf(node.getUpdatedAt()))
              )
            )
          );
          break;
        case UPDATED_AT_DESC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(
                new ImmutableTriple(Db.Node.UPDATED_AT, "<", String.valueOf(node.getUpdatedAt()))
              )
            )
          );
          break;
        case CREATED_AT_ASC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(
                new ImmutableTriple(Db.Node.CREATED_AT, ">", String.valueOf(node.getCreatedAt()))
              )
            )
          );
          break;
        case CREATED_AT_DESC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(
                new ImmutableTriple(Db.Node.CREATED_AT, "<", String.valueOf(node.getCreatedAt()))
              )
            )
          );
          break;
        case SIZE_ASC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(new ImmutableTriple(Db.Node.SIZE, "<", String.valueOf(node.getSize())))
            )
          );
          break;
        case SIZE_DESC:
          nextPage.setKeySet(
            buildKeyset(
              node.getNodeCategory(),
              node.getId(),
              Optional.of(new ImmutableTriple(Db.Node.SIZE, ">", String.valueOf(node.getSize())))
            )
          );
          break;
      }
    });
    if (!sort.isPresent()) {
      nextPage.setKeySet(
        buildKeyset(
          node.getNodeCategory(),
          node.getId(),
          Optional.empty()
        )
      );
    }
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.registerModule(new Jdk8Module());
    try {
      return Base64.getEncoder().encodeToString(mapper.writeValueAsString(nextPage).getBytes());
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

  }

  /**
   * This method decodes a pageToken and maps it to a {@link PageQuery} class for retrieving
   * pagination params
   *
   * @param token the given token for retrieving the next page of data
   *
   * @return the {@link PageQuery} class containing pagination params
   */
  private PageQuery decodePageToken(String token) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      return mapper.readValue(new String(Base64.getDecoder().decode(token)), PageQuery.class);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  /**
   * Directly retrieves from DB a node given its id
   *
   * @param nodeId the id of the node to retrieve
   *
   * @return
   */
  private Optional<Node> getRealNode(String nodeId) {
    String normalizedId = nodeId + StringUtils.repeat(" ", 36 - nodeId.length());
    return mDB.getEbeanDatabase()
      .find(Node.class)
      .where()
      .idEq(normalizedId)
      .findOneOrEmpty();
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
    Optional<NodeSort> sort,
    Optional<Boolean> flagged,
    Optional<String> folderId,
    Optional<Boolean> cascade,
    Optional<Boolean> sharedWithMe,
    Optional<Boolean> sharedByMe,
    Optional<Boolean> directShare,
    List<String> keywords,
    Optional<String> keyset
  ) {

    SearchBuilder search = new SearchBuilder(mDB.getEbeanDatabase(), userId);

    long startTime = java.lang.System.nanoTime();

    if (keywords.size() > 0) {
      search.setKeywords(keywords);
    }
    flagged.ifPresent(search::setFlagged);
    folderId.ifPresent(fId -> search.setFolderId(fId, cascade.orElse(true)));
    sharedWithMe.ifPresent(swm -> search.setSharedWithMe(userId, swm));
    sharedByMe.ifPresent(search::setSharedByMe);
    directShare.ifPresent(search::setDirectShare);

    search.setLimit(limit);
    keyset.ifPresent(search::setKeyset);

    sort.map(s -> {
        if (s.equals(NodeSort.SIZE_ASC) || s.equals(NodeSort.SIZE_DESC)) {
          search.setSort(s).setSort(NodeSort.NAME_ASC);
        } else {
          search.setSort(NodeSort.TYPE_ASC).setSort(sort.get());

        }
        return null;
      })
      .orElseGet(() -> {
        search.setSort(NodeSort.TYPE_ASC);
        return null;
      });

    List<Node> nodes = search.build().findList();

    long endTime = java.lang.System.nanoTime();
    long totalTime = (endTime - startTime) / 1_000_000;

    logger.info("Search time in milliseconds : " + totalTime);

    return nodes;
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
    List<String> keywords,
    Optional<String> pageToken
  ) {

    return pageToken.map(token -> {
        PageQuery params = decodePageToken(token);
        List<Node> nodes = doFind(userId,
          params.getLimit(),
          params.getSort().map(NodeSort::valueOf),
          params.getFlagged(),
          params.getFolderId(),
          params.getCascade(),
          params.getSharedWithMe(),
          params.getSharedByMe(),
          params.getDirectShare(),
          params.getKeywords(),
          params.getKeySet()
        );

        if (nodes.size() == params.getLimit()) {
          return new ImmutablePair<List<Node>, String>(nodes,
            createPageToken(nodes.get(nodes.size() - 1),
              params.getLimit(),
              params.getSort().map(NodeSort::valueOf),
              params.getFlagged(),
              params.getFolderId(),
              params.getCascade(),
              params.getSharedWithMe(),
              params.getSharedByMe(),
              params.getDirectShare(),
              params.getKeywords()
            )
          );
        } else {
          return new ImmutablePair<List<Node>, String>(nodes, null);
        }
      })
      .orElseGet(() -> {
        Integer realLimit = limit
          .map(l -> (l >= Files.Config.Pagination.LIMIT)
            ? Files.Config.Pagination.LIMIT
            : l)
          .orElse(Files.Config.Pagination.LIMIT);

        List<Node> nodes = doFind(userId,
          realLimit,
          sort,
          flagged,
          folderId,
          cascade,
          sharedWithMe,
          sharedByMe,
          directShare,
          keywords,
          Optional.empty()
        );

        if (nodes.size() == realLimit) {
          return new ImmutablePair<List<Node>, String>(nodes,
            createPageToken(nodes.get(nodes.size() - 1),
              realLimit,
              sort,
              flagged,
              folderId,
              cascade,
              sharedWithMe,
              sharedByMe,
              directShare,
              keywords)
          );
        } else {
          return new ImmutablePair<List<Node>, String>(nodes, null);
        }
      });
  }

  @Override
  public Optional<Node> getNode(String nodeId) {
    return  getRealNode(nodeId);
  }

  /**
   * Directly retrieves from DB a list of nodes
   *
   * @param nodeIds the list of nodes to retrieve
   * @param sort the sorting for the list of nodes
   *
   * @return
   */
  private List<Node> getRealNodes(
    List<String> nodeIds,
    Optional<NodeSort> sort
  ) {
    Query<Node> query = mDB.getEbeanDatabase()
      .createQuery(Node.class)
      .where()
      .idIn(nodeIds)
      .query();

    sort.map(s -> s.getOrderEbeanQuery(query));
    return query.findList();
  }

  @Override
  public Stream<Node> getNodes(
    List<String> nodeIds,
    Optional<NodeSort> sort
  ) {
    return getRealNodes(nodeIds, sort).stream();
  }

  @Override
  public List<String> getChildrenIds(
    String nodeId,
    Optional<NodeSort> sort,
    Optional<String> userId,
    boolean showMarked
  ) {
    Query<Node> query = mDB.getEbeanDatabase()
      .createQuery(Node.class)
      .select(Files.Db.Node.ID)
      .where()
      .eq(Files.Db.Node.PARENT_ID, nodeId)
      .query();

    if (nodeId.equals(RootId.LOCAL_ROOT) && userId.isPresent()) {
      query
        .where()
        .eq(Db.Node.OWNER_ID, userId.get());
    }

    sort
      .map(s -> {
        if (s.equals(NodeSort.SIZE_ASC) || s.equals(NodeSort.SIZE_DESC)) {
          s.getOrderEbeanQuery(query);
          NodeSort.NAME_ASC.getOrderEbeanQuery(query);
        } else {
          NodeSort.TYPE_ASC.getOrderEbeanQuery(query);
          s.getOrderEbeanQuery(query);
        }
        return s;
      })
      .orElseGet(() -> {
        NodeSort.TYPE_ASC.getOrderEbeanQuery(query);
        return null;
      });

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
    Long size
  ) {
    Node node = new Node(
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
      size
    );
    mDB.getEbeanDatabase().save(node);

    return getNode(nodeId).get();
  }

  @Override
  public boolean deleteNode(String nodeId) {
    return getNode(nodeId)
      .map(node -> mDB.getEbeanDatabase().delete(node))
      .orElse(false);
  }

  @Override
  public int deleteNodes(List<String> nodesIds) {
    return mDB.getEbeanDatabase()
      .find(Node.class)
      .where()
      .in(Files.Db.Node.ID, nodesIds)
      .delete();
  }

  @Override
  public void flagForUser(
    String nodeId,
    String userId,
    boolean flag
  ) {
    getCustomAttributesForUser(nodeId, userId)
      .map(customAttributes -> customAttributes.setFlag(flag))
      .orElseGet(() ->
      {
        NodeCustomAttributes customAttributes = new NodeCustomAttributes(nodeId, userId, flag);
        customAttributes.save();
        return customAttributes;
      });
  }

  @Override
  public boolean isFlaggedForUser(
    String nodeId,
    String userId
  ) {
    return getCustomAttributesForUser(nodeId, userId)
      .map(NodeCustomAttributes::getFlag)
      .orElse(false);
  }

  /**
   * Retrieves the customAttributes for the specific node and user.
   *
   * @param nodeId the id of the node to retrieve the custom attributes
   * @param userId the id of the user which to retrieve the custom attributes
   *
   * @return {@link NodeCustomAttributes} if there are custom attributes saved for the user,
   * otherwise it returns Optional.empty();
   */
  private Optional<NodeCustomAttributes> getCustomAttributesForUser(
    String nodeId,
    String userId
  ) {
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
  public void trashNode(
    String nodeId,
    String parentId
  ) {
    TrashedNode tNode = new TrashedNode(nodeId, parentId);
    mDB.getEbeanDatabase().save(tNode);
  }

  @Override
  public void restoreNode(String nodeId) {
    mDB.getEbeanDatabase()
      .find(TrashedNode.class)
      .where()
      .eq(Db.Trashed.NODE_ID, nodeId)
      .delete();
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
  public int moveNodes(
    List<String> nodesIds,
    Node destinationFolder
  ) {
    String ancestorIds = NodeType.ROOT.equals(destinationFolder.getNodeType())
      ? destinationFolder.getId()
      : destinationFolder.getAncestorIds() + "," + destinationFolder.getId();

    int numberMovedNodes = mDB.getEbeanDatabase()
      .update(Node.class)
      .set(Files.Db.Node.PARENT_ID, destinationFolder.getId())
      .set(Files.Db.Node.UPDATED_AT, System.currentTimeMillis())
      .set(Db.Node.ANCESTOR_IDS, ancestorIds)
      .where()
      .in(Files.Db.Node.ID, nodesIds)
      .update();

    return numberMovedNodes;
  }

  @Override
  public List<Node> getRootsList() {
    return mDB.getEbeanDatabase()
      .find(Node.class)
      .where()
      .eq(Db.Node.CATEGORY, 0)
      .findList();
  }

  @Override
  public Optional<Node> getNodeByName(
    String nodeName,
    String folderId,
    String nodeOwner
  ) {
    Query<Node> query = mDB.getEbeanDatabase()
      .find(Node.class)
      .where()
      .eq(Db.Node.NAME, nodeName)
      .eq(Db.Node.PARENT_ID, folderId)
      .eq(Db.Node.OWNER_ID, nodeOwner)
      .query();

    // We could use query.findOneOrEmpty() but if, for some reason, there are multiple nodes
    // with the same name the query would explode. So to be extra conservative we fetch all the
    // resulting node and then we return the first one
    return query
      .findList()
      .stream()
      .findFirst();
  }
}
