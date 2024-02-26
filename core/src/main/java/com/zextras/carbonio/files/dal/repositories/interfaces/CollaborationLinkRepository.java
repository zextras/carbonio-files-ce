// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * This is the only class allowed to execute CRUD operations on a {@link CollaborationLink} element.
 * In particular, it can create a new {@link CollaborationLink}, access it and delete an existing
 * one.
 */
public interface CollaborationLinkRepository {

  /**
   * Creates a new {@link CollaborationLink} and, after saving it in the database, it returns the
   * {@link CollaborationLink} just created.
   *
   * <p>This method considers the parameters in input already valid so it does not do any kind of
   * control on them.
   *
   * @param linkId is an {@link UUID} representing the internal unique identifier of the
   *     collaboration link to create
   * @param nodeId is a {@link String} representing the unique identifier of the node which the link
   *     is created to
   * @param invitationId is a {@link String} of 8 characters representing the public identifier
   *     (hash) of the collaboration link to create
   * @param permissions is a {@link SharePermission} representing the permission rights that the
   *     share will be created when this collaboration link will be used
   * @return the {@link CollaborationLink} just created and saved in the database.
   */
  CollaborationLink createLink(
      UUID linkId, String nodeId, String invitationId, SharePermission permissions);

  /**
   * Given an internal collaboration link identifier, it allows to retrieve a {@link
   * CollaborationLink} from the database if it exists.
   *
   * @param linkId is a {@link UUID} representing the unique identifier of the collaboration link to
   *     retrieve
   * @return an {@link Optional} containing the requested {@link CollaborationLink} if it exists,
   *     otherwise it returns an {@link Optional#empty()}.
   */
  Optional<CollaborationLink> getLinkById(UUID linkId);

  /**
   * Given a public invitation link identifier, it allows to retrieve a {@link CollaborationLink}
   * from the database if it exists.
   *
   * @param invitationId is a {@link String} of 8 characters representing the public identifier
   *     (hash) of the collaboration link to retrieve
   * @return an {@link Optional} containing the requested {@link CollaborationLink} if it exists,
   *     otherwise it returns an {@link Optional#empty()}.
   */
  Optional<CollaborationLink> getLinkByInvitationId(String invitationId);

  /**
   * Given a node identifier, it allows to retrieve a stream of {@link CollaborationLink} from the
   * database if there are links associated to the related node.
   *
   * @param nodeId is a {@link String} representing the unique identifier of the node which a list
   *     of links are associated to
   * @return a {@link Stream} containing the requested {@link CollaborationLink}s if they exists,
   *     otherwise it returns an empty {@link Stream}.
   */
  Stream<CollaborationLink> getLinksByNodeId(String nodeId);

  /**
   * Given a collection of identifiers, it deletes the existing {@link CollaborationLink}s from the
   * database.
   *
   * <p>if a collaboration link identifier is not associated to a {@link CollaborationLink} then the
   * delete operation for that specific identifier does nothing.
   *
   * @param linkIds is a {@link Collection} of {@link UUID}s representing the unique identifier of
   *     collaboration links to delete
   */
  void deleteLinks(Collection<UUID> linkIds);
}
