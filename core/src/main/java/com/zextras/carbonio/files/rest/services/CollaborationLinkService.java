// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.datafetchers.ShareDataFetcher;
import io.vavr.control.Try;
import java.text.MessageFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class CollaborationLinkService {

  private final CollaborationLinkRepository collaborationLinkRepository;
  private final NodeRepository              nodeRepository;
  private final ShareRepository             shareRepository;
  private final ShareDataFetcher            shareDataFetcher;

  @Inject
  public CollaborationLinkService(
    CollaborationLinkRepository collaborationLinkRepository,
    NodeRepository nodeRepository,
    ShareRepository shareRepository,
    ShareDataFetcher shareDataFetcher
  ) {
    this.collaborationLinkRepository = collaborationLinkRepository;
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
    this.shareDataFetcher = shareDataFetcher;
  }

  public Try<Node> createShareByInvitationId(
    String invitationId,
    String requesterId
  ) {
    return collaborationLinkRepository
      .getLinkByInvitationId(invitationId)
      .map(collaborationLink ->
        nodeRepository
          .getNode(collaborationLink.getNodeId())
          .map(node -> {
            // If the owner itself has clicked on a collaboration link, the system does nothing.
            // However, the system return the success because the collaboration link and the node exist
            if (!node.getOwnerId().equals(requesterId)) {
              Optional<Share> optShare = shareRepository.getShare(
                collaborationLink.getNodeId(),
                requesterId
              );

              if (optShare.isPresent()) {
                optShare.get().setPermissions(ACL.decode(collaborationLink.getPermissions()));
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
                  Optional.empty()
                );
              }

              // TODO: This is temporary, we need to change the cascadeUpsertShare method as an
              //  utility method
              CompletableFuture.runAsync(() ->
                shareDataFetcher.cascadeUpsertShare(
                  collaborationLink.getNodeId(),
                  requesterId,
                  ACL.decode(collaborationLink.getPermissions()),
                  Optional.empty()
                )
              );
            }
            return Try.success(node);
          })
          .orElse(Try.failure(new NoSuchElementException(
            MessageFormat.format("Node {0} does not exist", collaborationLink.getNodeId())
          )))
      )
      .orElse(Try.failure(new NoSuchElementException(
        MessageFormat.format("Collaboration Link {0} does not exist", invitationId)
      )));
  }
}
