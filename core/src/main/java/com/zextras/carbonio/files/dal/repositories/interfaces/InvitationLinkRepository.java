// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.InvitationLink;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * <p>This is the only class allowed to execute CRUD operations on a {@link InvitationLink}
 * element. In particular it can create a new {@link InvitationLink}, update it, access to and
 * delete an existing one.</p>
 */
public interface InvitationLinkRepository {

  InvitationLink createLink(
    UUID linkId,
    String nodeId,
    String invitationId,
    SharePermission permissions
  );

  Optional<InvitationLink> getLinkById(UUID linkId);

  Optional<InvitationLink> getLinkByInvitationId(String invitationId);

  Stream<InvitationLink> getLinksByNodeId(String nodeId);

  void deleteLinks(Collection<UUID> linkIds);
}
