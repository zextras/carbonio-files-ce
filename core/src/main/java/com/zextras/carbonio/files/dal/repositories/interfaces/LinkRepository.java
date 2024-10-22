// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <p>This is the only class allowed to execute CRUD operations on a {@link Link} element.</p>
 * <p>In particular it can create a new {@link Link}, update it, access to and delete an existing
 * one.</p>
 */
public interface LinkRepository {

  Link createLink(
    String linkId,
    String nodeId,
    String publicId,
    Optional<Long> optExpiresAt,
    Optional<String> optDescription
  );

  Optional<Link> getLinkById(String linkId);

  Optional<Link> getLinkByNotExpiredPublicId(String publicId);

  Stream<Link> getLinksByNodeId(
    String nodeId,
    LinkSort sort
  );

  Link updateLink(Link link);

  void deleteLink(String linkId);

  void deleteLinksBulk(Collection<String> linkIds);

  /**
   * Checks whether a given {@link Node} has at least one unexpired public link associated with it,
   * which allows access to the node without requiring permissions.
  *
   * @param node is a given {@link Node} to check.
   * @return true if the {@link Node} has at least one public link associated, false otherwise.
   */
  boolean hasNodeANotExpiredPublicLink(Node node);

  /**
   * Checks whether a {@link Link} obtained from linkId is a valid link (node is the correct node
   * and link is not expired) for the provided node, which allows access to the node without requiring permissions.
  *
   * @param publicLinkId is the {@link Link} public id to check for correctness of node and expiration.
   * @param node is the {@link Node} to check the correctness of node id inside link.
   * @return true if the {@link Link} obtained by linkId has nodeId associated and is not expired, false otherwise.
   */
  boolean isLinkValidForNode(String publicLinkId, Node node);
}
