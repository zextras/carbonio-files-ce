// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.ShareSort;
import java.util.List;
import java.util.Optional;

/**
 * <p>This is the only class allowed to execute CRUD operations on a {@link Share} element.</p>
 * <p>In particular it can create a new {@link Share}, access to and delete an existing one.</p>
 */
public interface ShareRepository {

  /**
   * Retrieves a {@link Share} from the database or from the cache if it was recently requested.
   *
   * @param nodeId is a {@link String} of the id of shared node.
   * @param userId is a {@link String} of the target user id which the node is shared to.
   *
   * @return an {@link Optional) containing the {@link Share} requested if exists.
   */
  Optional<Share> getShare(
    String nodeId,
    String userId
  );

  /**
   * <p>Creates a new {@link Share} saving it in the database, then returns an {@link Optional} of
   * the {@link Share} just created.</p>
   * <p>This method returns an optional because creation can fail when it tries to create a share
   * that already exists for a user on a particular node.</p>
   *
   * @param nodeId is a {@link String} of the node id.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared
   * to.
   * @param permissions is a {@link ACL} representing the permissions of this share.
   * @param direct is a boolean used to determine if the share is direct or inherited.
   * @param createdViaCollaborationLink
   * @param expireTimestamp is an {@link Optional<Long>} for creating a temporary share with an
   * expiration date.
   *
   * @return an {@link Optional} of the new {@link Share} just created and saved in the database.
   */
  Optional<Share> upsertShare(
    String nodeId,
    String targetUserId,
    ACL permissions,
    Boolean direct,
    Boolean createdViaCollaborationLink,
    Optional<Long> expireTimestamp
  );

  /**
   * <p>Creates new {@link Share} for a list of nodes saving it in the database.
   * <p>This method considers the parameters in input already valid so it does not do any kind of
   * control on them.</p> This method is created as a utility for the propagation of the share on
   * sub nodes.
   *
   * @param nodeIds is a {@link List<String>} of the nodeIds where to create the share.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared
   * to.
   * @param permissions is a {@link ACL} representing the permissions of this share.
   * @param direct is a {@link Boolean} used to determine if the share is direct or inherited.
   * @param expireTimestamp is an {@link Optional<Long>} for creating a temporary share with an
   * expiration date.
   */
  void upsertShareBulk(
    List<String> nodeIds,
    String targetUserId,
    ACL permissions,
    Boolean direct,
    Boolean createdViaCollaborationLink,
    Optional<Long> expireTimestamp
  );

  /**
   * This method is used to update a share on database. It requires the updated share and it will
   * update it on database and cache.
   *
   * @param share is the {@link Share} to update
   *
   * @return the updated {@link Share}
   */
  Share updateShare(Share share);

  /**
   * Deletes a {@link Share} from the database and from the cache if it was recently requested.
   *
   * @param nodeId is a {@link String} of the id of shared node.
   * @param targetUserId is a {@link String} of the target user id which the node is shared to.
   *
   * @return true if the {@link Share} exists and the deletion from the database is done
   * successfully, false otherwise.
   */
  boolean deleteShare(
    String nodeId,
    String targetUserId
  );

  /**
   * Deletes the shares on a list of nodes for a user. This method considers the parameters in input
   * already valid so it does not do any kind of control on them. This method is created as a
   * utility for the propagation of the share deletion on sub nodes.
   *
   * @param nodeIds is a {@link List<String>} of the nodeIds where to delete the share.
   * @param targetUserId is a {@link String} of the target user.
   */
  void deleteSharesBulk(
    List<String> nodeIds,
    String targetUserId
  );

  /**
   * Deletes all shares of each given node. This method considers the parameters in input already
   * valid so it does not do any kind of control on them.
   *
   * @param nodeIds is a {@link List<String>} of the nodeIds where to delete all shares.
   */
  void deleteSharesBulk(List<String> nodeIds);

  /**
   * Returns the list of shares, if present, for the nodes requested for the target user.
   *
   * @param nodeIds is a {@link List<String>} of the nodes I'm requesting the shares.
   * @param targetUserId is a {@link String} of the target user id which the nodes are shared to.
   *
   * @return the {@link List} of found {@link Share}s.
   */
  List<Share> getShares(
    List<String> nodeIds,
    String targetUserId
  );

  /**
   * Returns the list of shares, if present, for the specific node. If targetUserIds is not empty it
   * will return only the shares for the requested users.
   *
   * @param nodeId is a {@link String} representing the id of the node i'm requesting shares.
   * @param targetUserIds is a {@link List<String>} of the target user ids for which I'm requesting
   * the shares.
   *
   * @return the {@link List} of found {@link Share}s.
   */
  List<Share> getShares(
    String nodeId,
    List<String> targetUserIds
  );

  /**
   * @param nodeIds is a {@link List<String>} of the node ids. For each one of them the query
   * retrieves all the related shares.
   *
   * @return a {@link List} of found {@link Share}s for the specific node ids in input.
   */
  List<Share> getShares(List<String> nodeIds);

  /**
   * Returns the list of userId for which the given node is shared.
   *
   * @param nodeId is a {@link String} of the id of the node for which retrieve the shares.
   * @param sorts is a {@link List<ShareSort>} used for ordering the returned shares.
   *
   * @return the {@link List<String>} of ids of users.
   */
  List<String> getSharesUsersIds(
    String nodeId,
    List<ShareSort> sorts
  );
}
