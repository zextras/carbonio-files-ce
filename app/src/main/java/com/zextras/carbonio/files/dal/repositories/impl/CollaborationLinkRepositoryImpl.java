// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Panache implementation of {@link CollaborationLinkRepository}. Replaces the old Ebean-based
 * {@code CollaborationLinkRepositoryEbean}.
 */
@ApplicationScoped
public class CollaborationLinkRepositoryImpl
    implements CollaborationLinkRepository, PanacheRepositoryBase<CollaborationLink, UUID> {

  @Override
  @Transactional
  public CollaborationLink createLink(
      UUID linkId, String nodeId, String invitationId, SharePermission permissions) {

    CollaborationLink collaborationLink =
        new CollaborationLink(linkId, nodeId, invitationId, Instant.now(), permissions.encode());

    persist(collaborationLink);
    return collaborationLink;
  }

  @Override
  public Optional<CollaborationLink> getLinkById(UUID linkId) {
    return findByIdOptional(linkId);
  }

  @Override
  public Optional<CollaborationLink> getLinkByInvitationId(String invitationId) {
    return find("invitationId", invitationId).firstResultOptional();
  }

  @Override
  public Stream<CollaborationLink> getLinksByNodeId(String nodeId) {
    return find("nodeId", nodeId).stream();
  }

  @Override
  @Transactional
  public void deleteLinks(Collection<UUID> linkIds) {
    // Fetch-then-remove (rather than a bulk "delete ... where id in" JPQL statement) so entities
    // already managed in the current persistence context are correctly evicted; JPA bulk
    // statements bypass the first-level cache and would otherwise leave stale managed instances
    // behind for the rest of the transaction.
    list("id in ?1", linkIds).forEach(this::delete);
  }
}
