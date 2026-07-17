// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.datafetchers.ShareDataFetcher;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.text.MessageFormat;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Quarkus port of the legacy Guice {@code CollaborationLinkService}. Resolves a {@link
 * com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink} by its public invitation id and
 * creates/updates a direct share for the requester with the link's permission, propagating it on
 * the sub-tree via {@link ShareDataFetcher#cascadeUpsertShare} (the same helper used by the
 * GraphQL {@code createShare} mutation) — reused as-is rather than reimplemented.
 *
 * <p>One deliberate deviation from the legacy behaviour: {@code cascadeUpsertShare} is invoked
 * SYNCHRONOUSLY here instead of via {@code CompletableFuture.runAsync(...)}. The legacy call was
 * already flagged "temporary" (see the TODO retained below); on Quarkus it cannot run
 * fire-and-forget on a separate thread because it needs the request-scoped {@code EntityManager},
 * which is only bound to the calling (request) thread — the same constraint documented on {@link
 * BlobService}'s ZIP-planning code.
 */
@ApplicationScoped
public class CollaborationLinkService {

  private final CollaborationLinkRepository collaborationLinkRepository;
  private final NodeRepository nodeRepository;
  private final ShareRepository shareRepository;
  private final ShareDataFetcher shareDataFetcher;

  @Inject
  public CollaborationLinkService(
      CollaborationLinkRepository collaborationLinkRepository,
      NodeRepository nodeRepository,
      ShareRepository shareRepository,
      ShareDataFetcher shareDataFetcher) {
    this.collaborationLinkRepository = collaborationLinkRepository;
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
    this.shareDataFetcher = shareDataFetcher;
  }

  public Try<Node> createShareByInvitationId(String invitationId, String requesterId) {
    return collaborationLinkRepository
        .getLinkByInvitationId(invitationId)
        .map(
            collaborationLink ->
                nodeRepository
                    .getNode(collaborationLink.getNodeId())
                    .map(
                        node -> {
                          // If the owner itself has clicked on a collaboration link, the system
                          // does nothing. However, the system returns success because the
                          // collaboration link and the node exist.
                          if (!node.getOwnerId().equals(requesterId)) {
                            Optional<Share> optShare =
                                shareRepository.getShare(
                                    collaborationLink.getNodeId(), requesterId);

                            if (optShare.isPresent()) {
                              optShare
                                  .get()
                                  .setPermissions(ACL.decode(collaborationLink.getPermissions()));
                              optShare.get().setDirect(true);
                              optShare.get().setCreatedViaLink(true);
                              shareRepository.updateShare(optShare.get());
                            } else {
                              shareRepository.upsertShare(
                                  collaborationLink.getNodeId(),
                                  requesterId,
                                  ACL.decode(collaborationLink.getPermissions()),
                                  true,
                                  true,
                                  Optional.empty());
                            }

                            // TODO: This is temporary, we need to change the cascadeUpsertShare
                            //  method as a utility method. Called synchronously (see class
                            //  javadoc): the request-scoped EntityManager is not available off
                            //  the request thread on Quarkus.
                            shareDataFetcher.cascadeUpsertShare(
                                collaborationLink.getNodeId(),
                                requesterId,
                                ACL.decode(collaborationLink.getPermissions()),
                                Optional.empty());
                          }
                          return Try.success(node);
                        })
                    .orElse(
                        Try.failure(
                            new NoSuchElementException(
                                MessageFormat.format(
                                    "Node {0} does not exist", collaborationLink.getNodeId())))))
        .orElse(
            Try.failure(
                new NoSuchElementException(
                    MessageFormat.format(
                        "Collaboration Link {0} does not exist", invitationId))));
  }
}
