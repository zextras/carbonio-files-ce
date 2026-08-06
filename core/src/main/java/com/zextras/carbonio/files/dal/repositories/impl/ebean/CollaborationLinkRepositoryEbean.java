// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.DatabaseManager;
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
  private final DatabaseManager databaseManagerFlyway;

  @Inject
  public CollaborationLinkRepositoryEbean(Clock clock, DatabaseManager databaseManagerFlyway) {
    this.clock = clock;
    this.databaseManagerFlyway = databaseManagerFlyway;
  }

  @Override
  public CollaborationLink createLink(
      UUID linkId, String nodeId, String invitationId, SharePermission permissions) {

    CollaborationLink collaborationLink =
        new CollaborationLink(linkId, nodeId, invitationId, clock.instant(), permissions.encode());

    databaseManagerFlyway.getEbeanDatabase().insert(collaborationLink);

    return collaborationLink;
  }

  @Override
  public Optional<CollaborationLink> getLinkById(UUID linkId) {
    return databaseManagerFlyway
        .getEbeanDatabase()
        .find(CollaborationLink.class)
        .where()
        .idEq(linkId)
        .findOneOrEmpty();
  }

  @Override
  public Optional<CollaborationLink> getLinkByInvitationId(String invitationId) {
    return databaseManagerFlyway
        .getEbeanDatabase()
        .find(CollaborationLink.class)
        .where()
        .eq(Constants.Db.CollaborationLink.INVITATION_ID, invitationId)
        .findOneOrEmpty();
  }

  @Override
  public Stream<CollaborationLink> getLinksByNodeId(String nodeId) {
    return databaseManagerFlyway
        .getEbeanDatabase()
        .find(CollaborationLink.class)
        .where()
        .eq(Constants.Db.CollaborationLink.NODE_ID, nodeId)
        .findList()
        .stream();
  }

  @Override
  public void deleteLinks(Collection<UUID> linkIds) {
    databaseManagerFlyway
        .getEbeanDatabase()
        .find(CollaborationLink.class)
        .where()
        .idIn(linkIds)
        .delete();
  }
}
