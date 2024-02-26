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
import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class CollaborationLinkRepositoryEbean implements CollaborationLinkRepository {

  private final Clock clock;
  private final EbeanDatabaseManager ebeanDatabaseManager;

  @Inject
  public CollaborationLinkRepositoryEbean(Clock clock, EbeanDatabaseManager ebeanDatabaseManager) {
    this.clock = clock;
    this.ebeanDatabaseManager = ebeanDatabaseManager;
  }

  @Override
  public CollaborationLink createLink(
      UUID linkId, String nodeId, String invitationId, SharePermission permissions) {

    CollaborationLink collaborationLink =
        new CollaborationLink(linkId, nodeId, invitationId, clock.instant(), permissions.encode());

    ebeanDatabaseManager.getEbeanDatabase().insert(collaborationLink);

    return collaborationLink;
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
  public Optional<CollaborationLink> getLinkByInvitationId(String invitationId) {
    return ebeanDatabaseManager
        .getEbeanDatabase()
        .find(CollaborationLink.class)
        .where()
        .eq(Files.Db.CollaborationLink.INVITATION_ID, invitationId)
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
