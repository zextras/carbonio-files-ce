// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class CollaborationLinkRepositoryEbean implements CollaborationLinkRepository {

  private final EbeanDatabaseManager ebeanDatabaseManager;

  @Inject
  public CollaborationLinkRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager) {
    this.ebeanDatabaseManager = ebeanDatabaseManager;
  }

  @Override
  public CollaborationLink createLink(
    UUID linkId,
    String nodeId,
    String collaborationId,
    SharePermission permissions
  ) {
    CollaborationLink collaborationLink = new CollaborationLink(
      linkId,
      nodeId,
      collaborationId,
      permissions.encode()
    );

    ebeanDatabaseManager
      .getEbeanDatabase()
      .insert(collaborationLink);

    return getLinkById(linkId).get();
  }

  @Override
  public Optional<CollaborationLink> getLinkById(UUID linkId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(CollaborationLink.class)
      .where()
      .idEq(linkId)
      .findOneOrEmpty();
  }

  @Override
  public Optional<CollaborationLink> getLinkByCollaborationId(String collaborationId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(CollaborationLink.class)
      .where()
      .eq(Files.Db.CollaborationLink.COLLABORATION_ID, collaborationId)
      .findOneOrEmpty();
  }

  @Override
  public Stream<CollaborationLink> getLinksByNodeId(String nodeId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(CollaborationLink.class)
      .where()
      .eq(Files.Db.CollaborationLink.NODE_ID, nodeId)
      .findList()
      .stream();
  }

  @Override
  public void deleteLinks(Collection<UUID> linkIds) {
    ebeanDatabaseManager
      .getEbeanDatabase()
      .find(CollaborationLink.class)
      .where()
      .idIn(linkIds)
      .delete();
  }
}
