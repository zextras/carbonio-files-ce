// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.InvitationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.InvitationLinkRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class InvitationLinkRepositoryEbean implements InvitationLinkRepository {

  private final EbeanDatabaseManager ebeanDatabaseManager;

  @Inject
  public InvitationLinkRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager) {
    this.ebeanDatabaseManager = ebeanDatabaseManager;
  }

  @Override
  public InvitationLink createLink(
    UUID linkId,
    String nodeId,
    String invitationId,
    SharePermission permissions
  ) {
    InvitationLink invitationLink = new InvitationLink(
      linkId,
      nodeId,
      invitationId,
      permissions.encode()
    );

    ebeanDatabaseManager
      .getEbeanDatabase()
      .insert(invitationLink);

    return getLinkById(linkId).get();
  }

  @Override
  public Optional<InvitationLink> getLinkById(UUID linkId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(InvitationLink.class)
      .where()
      .idEq(linkId)
      .findOneOrEmpty();
  }

  @Override
  public Optional<InvitationLink> getLinkByInvitationId(String invitationId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(InvitationLink.class)
      .where()
      .eq(Db.InvitationLink.INVITATION_ID, invitationId)
      .findOneOrEmpty();
  }

  @Override
  public Stream<InvitationLink> getLinksByNodeId(String nodeId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(InvitationLink.class)
      .where()
      .eq(Db.InvitationLink.NODE_ID, nodeId)
      .findList()
      .stream();
  }

  @Override
  public void deleteLinks(Collection<UUID> linkIds) {
    ebeanDatabaseManager
      .getEbeanDatabase()
      .find(InvitationLink.class)
      .where()
      .idIn(linkIds)
      .delete();
  }
}
