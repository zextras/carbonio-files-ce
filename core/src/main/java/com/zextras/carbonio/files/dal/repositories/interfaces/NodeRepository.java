// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * <p>This is the only class allowed to execute CRUD operations on a {@link Node} element.</p>
 * <p>It's method must be implemented by specific implementations depending on DB and ORM used.</p>
 */
public interface NodeRepository {

  /**
   * <p>Allows to retrieve a {@link Node} from the database or from the cache if it was recently
   * requested.</p>
   * <p>First it checks if the {@link Node} is on the database and then it returns a {@link
   * Node}.</p>
   *
   * @param nodeId is a {@link String} representing the id of the node to retrieve.
   *
   * @return an {@link Optional<Node>} containing the {@link Node} requested.
   */
  Optional<Node> getNode(String nodeId);

  /**
   * <p>Allows to retrieve a list of {@link Node}s from the database filter by chosen criteria.</p>
   *
   * @param userId is a {@link String} representing the user for which we'll limit the visibility of
   * the nodes
   * @param sort is an {@link Optional<NodeSort>} used for ordering the nodes
   * @param flagged is an {@link Optional<Boolean>} to search only nodes with or without flag
   * @param folderId is an {@link Optional<String>} to search only nodes on the subtree of that
   * folder
   * @param cascade is a {@link Optional<Boolean>} to manage search behaviour on folder, if true it
   * will search on the whole subtree, if false it will search only direct children of the folder
   * @param sharedWithMe is an {@link Optional<Boolean>} to search only nodes that have been shared
   * with me
   * @param sharedByMe is an {@link Optional<Boolean>} to search only nodes I own that are shared
   * @param limit is an {@link Optional<Integer>} used to limit the number of nodes returned
   * @param optNodeType is an {@link Optional<NodeType>} to search only nodes having that type
   * @param optOwnerId is an {@link Optional<String>} to search only nodes owned by that user
   * @param pageToken is an {@link Optional<Boolean>} to search for the next page of a previous find
   * via a given pageToken
   * @param keywords is a {@link List<String>} to search for specific keywords in the name or
   * description of the node
   *
   * @return the list of found {@link Node}s.
   */
  ImmutablePair<List<Node>, String> findNodes(
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
    Optional<String> pageToken
  );

  String createPageToken(
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
    List<String> keywords
  ) throws JsonProcessingException;

  /**
   * Allows to retrieve a list of {@link Node}s from the database filter by chosen criteria.
   *
   * @param folderId is an {@link Optional<String>} to search only nodes on the subtree of that
   *     folder
   * @param limit is an {@link Integer} used to limit the number of nodes returned (It can be null).
   * @param pageToken is an {@link String} representing a token necessary to search for the next
   *     page (it can be null).
   * @return an {@link ImmutablePair} containing a {@link List} of found {@link Node}s and a {@link
   *     String} representing the page token to fetch the next page. The list could be empty if
   *     there aren't nodes found and the page token could be null if there isn't a next page.
   */
  ImmutablePair<List<Node>, String> publicFindNodes(
    String folderId, @Nullable Integer limit, @Nullable String pageToken);

  /**
   * <p>Allows to retrieve the list of {@link Node}s from the database or from the cache if already
   * present.</p>
   * <p>The retrieved list can be sorted using one or more {@link NodeSort}.</p>
   * <p>If there are already some nodes in cache, the ordering of the passed nodes will be
   * preserved, otherwise they will be retrieved with the default db ordering if no sorts is passed.
   * If you retrieved the ids with a particular sort, be sure to pass it to this function to
   * preserve it.</p>
   *
   * @param nodeIds is a {@link List<String>} representing the ids of the nodes to retrieve, node
   * order is preserved if cache is used
   * @param sort is the list of {@link NodeSort} to use for ordering the nodes if cache is not used
   *
   * @return a {@link Stream<Node>} containing the requested nodes
   */
  Stream<Node> getNodes(
    List<String> nodeIds,
    Optional<NodeSort> sort
  );

  /**
   * <p>Allows to retrieve the list of ids relative to the children of a specific {@link Node}.</p>
   * <p>The retrieved list can be sorted using one or more {@link NodeSort}.</p>
   *
   * @param nodeId is a {@link String} representing the id of the node
   * @param sort is a {@link NodeSort} to use for ordering the children ids
   * @param userId is a {@link Optional<String>} used to filter only owner nodes on root folder
   * @param showMarked if false, the only ids retrieved are those associated with children that are
   * not marked for deletion
   *
   * @return a {@link List<String>} containing the requested children ids.
   */
  List<String> getChildrenIds(
    String nodeId,
    Optional<NodeSort> sort,
    Optional<String> userId,
    boolean showMarked
  );

  /**
   * <p>Creates a new {@link Node} and, after saving it in the database, it returns the
   * {@link Node} just created.</p>
   * <p>This method considers the parameters in input already valid so it does not do any kind of
   * control on them.</p>
   *
   * @param nodeId is a {@link String} representing the id of the {@link Node}
   * @param creatorId is a {@link String} of the user is that created the node
   * @param ownerId is a {@link String} of the user that own the node
   * @param parentId is a {@link String} representing the id of the parent node
   * @param name is a {@link String} of the name of the node
   * @param description is a {@link String} of the description of the node
   * @param type is a {@link NodeType} representing the type of the node
   *
   * @return a {@link Node} just created and saved in the database.
   */
  Node createNewNode(
    String nodeId,
    String creatorId,
    String ownerId,
    String parentId,
    String name,
    String description,
    NodeType type,
    String ancestorIds,
    Long size
  );

  /**
   * <p>Deletes a {@link Node} from the database and from the cache if enabled.</p>
   * <p>if the {@link Node} does not exist, then the method does nothing and returns
   * <code>false</code>.</p>
   *
   * @param nodeId is a {@link String} representing the id of the node to delete
   *
   * @return <code>true</code> if the {@link Node} exists in the database, and if the deletion is
   * performed correctly, <code>false</code> otherwise.
   */
  boolean deleteNode(String nodeId);

  int deleteNodes(List<String> nodesIds);

  /**
   * <p>Changes the flag custom attribute for a {@link Node}.</p>
   * <p>if the {@link NodeCustomAttributes} does not exist, then the method
   * creates the custom attributes on the database for the {@link Node} and the user .</p>
   *
   * @param nodeId is a {@link String} representing the id of the node to flag
   * @param userId is a {@link String} representing the id of the user for which we have to flag the
   * node
   * @param flag is a boolean representing the flag value
   */
  void flagForUser(
    String nodeId,
    String userId,
    boolean flag
  );

  /**
   * <p>Returns the flag value for the couple {@link Node} and user.</p>
   *
   * @param nodeId is a {@link String} representing the id of the node to flag
   * @param userId is a {@link String} representing the id of the user for which we have to flag the
   * node
   */
  boolean isFlaggedForUser(
    String nodeId,
    String userId
  );

  /**
   * Gets the detail of a trashed node
   *
   * @param nodeId is a {@link String} representing the id of the trashed node
   *
   * @return the {@link Optional<TrashedNode>} if the node has been directly trashed
   */
  Optional<TrashedNode> getTrashedNode(String nodeId);

  List<String> getTrashedNodeIdsByOldParent(String oldParentId);

  /**
   * <p>Trashes a node putting it into the TRASHED table.</p>
   *
   * @param nodeId is a {@link String} representing the id of the node to trash
   * @param parentId is a {@link String} representing the id of the old parent of the trashed node
   */
  void trashNode(
    String nodeId,
    String parentId
  );

  /**
   * <p>Untrashes a node putting it into its original position if it still exists.
   * Otherwise it will put it in the LOCAL_ROOT.</p>
   *
   * @param nodeId is a {@link String} representing the id of the node to trash
   */
  void restoreNode(String nodeId);

  /**
   * <p>Moves a list of {@link Node}s to a destination folder.</p>
   * <p>This operation is done in bulk in one single database call./p>
   *
   * @param nodesIds a {@link List} of {@link String}s representing the ids of nodes to move.
   * @param destinationFolder is a {@link Node} representing the node of the destination folder.
   *
   * @return an integer representing the number of moved nodes. This value can be useful for testing
   * purposes.
   */
  int moveNodes(
    List<String> nodesIds,
    Node destinationFolder
  );

  /**
   * <p>Updates a {@link Node} on database.</p>
   *
   * @param node is the {@link Node} to update
   *
   * @return the update node
   */
  Node updateNode(Node node);

  /**
   * <p>Deletes from the database all nodes marked for deletion whose retention time exceeded the
   * value passed as parameter.</p>
   *
   * @param retentionTimestamp represents the number of milliseconds that a {@link Node} can pass in
   * trash before being deleted
   *
   * @return a boolean which is <code>true</code> if the deletion was performed correctly,
   * <code>false</code> otherwise
   */
  int deleteTrashedNodesOlderThan(Long retentionTimestamp);

  /**
   * <p>Returns all Nodes trashed.</p>
   *
   * @return the list of all Nodes trashed.
   */
  List<Node> getAllTrashedNodes(Long retentionTimestamp);

  /**
   * <p>Returns a {@link List} containing all the root folders.</p>
   *
   * @return the {@link List} of all the root folders.
   */
  List<Node> getRootsList();

  /**
   * Utility method to find check if a node with a certain name is already present inside a folder
   *
   * @param nodeName the name of the node to search
   * @param folderId the folder where to search
   *
   * @return an {@link Optional<Node>} with the node if it was found or empty
   */
  Optional<Node> getNodeByName(
    String nodeName,
    String folderId,
    String nodeOwner
  );

  List<Node> findNodesByOwner(String ownerId);

  Optional<Node> findFirstByOwner(String ownerId);

  void invertHiddenFlagNodes(List<Node> nodesToFlag);
}
