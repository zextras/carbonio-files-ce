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
 * This is the only class allowed to execute CRUD operations on a {@link Link} element. In
 * particular, it can create a new {@link Link}, update it, access to and delete an existing one.
 */
public interface LinkRepository {

  /**
   * Creates a new {@link Link} and, after saving it in the database, it returns the {@link Link}
   * just created.
   *
   * @param linkId is a {@link String} representing the internal unique identifier (UUID) of the
   *     link to create
   * @param nodeId is a {@link String} representing the unique identifier of the node which the link
   *     is created to
   * @param publicId is a {@link String} of 32 characters representing the public identifier (hash)
   *     of the link to create
   * @param optExpiresAt is an {@link Optional} of {@link Long} representing the expiration
   *     timestamp of the link to create if the value is present
   * @param optDescription is an {@link Optional} of {@link String} representing the description of
   *     the link to create if the value is present
   * @return the {@link Link} just created and saved in the database.
   */
  Link createLink(
      String linkId,
      String nodeId,
      String publicId,
      Optional<Long> optExpiresAt,
      Optional<String> optDescription);

  /**
   * Given an internal link identifier, it allows to retrieve a {@link Link} from the database if it
   * exists.
   *
   * @param linkId is a {@link String} representing the unique identifier (UUDI) of the link to
   *     retrieve
   * @return an {@link Optional} containing the requested {@link Link} if it exists, otherwise it
   *     returns an {@link Optional#empty()}.
   */
  Optional<Link> getLinkById(String linkId);

  /**
   * Given a public link identifier, it allows to retrieve a not expired {@link Link} from the
   * database if it exists.
   *
   * @param publicId is a {@link String} of 32 characters representing the public identifier (hash)
   *     of the link to retrieve
   * @return an {@link Optional} containing the requested {@link Link} if it exists, otherwise it
   *     returns an {@link Optional#empty()}.
   */
  Optional<Link> getLinkByNotExpiredPublicId(String publicId);

  /**
   * Given a node identifier, it allows to retrieve a stream of {@link Link} from the database if
   * there are links associated to the related node.
   *
   * @param nodeId is a {@link String} representing the unique identifier of the node which a list
   *     of links are associated to
   * @return a {@link Stream} containing the requested {@link Link}s if they exist, otherwise it
   *     returns an empty {@link Stream}.
   */
  Stream<Link> getLinksByNodeId(String nodeId, LinkSort sort);

  /**
   * Given an updated {@link Link}, it updates it in the database and returns the {@link Link} just
   * saved.
   *
   * @param link is the updated {@link Link} to save in the database
   * @return the {@link Link} just updated in the database.
   */
  Link updateLink(Link link);

  /**
   * Given a link identifier, it deletes the existing {@link Link} from the database.
   *
   * <p>if the link identifier is not associated to a {@link Link} then the delete operation does
   * nothing.
   *
   * @param linkId is a {@link String} representing the unique identifier of the link to delete
   */
  void deleteLink(String linkId);

  /**
   * Given a collection of identifiers, it deletes the existing {@link Link}s from the database.
   *
   * <p>if a link identifier is not associated to a {@link Link} then the delete operation for that
   * specific identifier does nothing.
   *
   * @param linkIds is a {@link Collection} of {@link String}s representing the unique identifier of
   *     links to delete
   */
  void deleteLinksBulk(Collection<String> linkIds);

  /**
   * Checks whether a given {@link Node} has at least one unexpired public link associated with it,
   * which allows access to the node without requiring permissions.
  *
   * @param node is a given {@link Node} to check.
   * @return true if the {@link Node} has at least one public link associated, false otherwise.
   */
  boolean hasNodeANotExpiredPublicLink(Node node);
}
