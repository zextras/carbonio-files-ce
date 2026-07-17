// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * P2c: validates the clean Panache/JPA {@code CollaborationLinkRepositoryImpl}, which replaces
 * {@code CollaborationLinkRepositoryEbean}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CollaborationLinkRepositoryIT {

  @Inject CollaborationLinkRepository collaborationLinkRepository;

  @Inject EntityManager entityManager;

  private Node persistParentNode(String nodeId) {
    Node node =
        new Node(
            nodeId,
            "creator-id",
            "owner-id",
            "parent-id",
            1L,
            1L,
            "collab-link-parent-" + nodeId,
            "description",
            NodeType.FOLDER,
            "",
            0L);
    entityManager.persist(node);
    return node;
  }

  @Test
  @TestTransaction
  void createLinkShouldPersistAndBeRetrievableById() {
    String nodeId = "22222222-2222-2222-2222-222222222221";
    persistParentNode(nodeId);
    UUID linkId = UUID.randomUUID();

    CollaborationLink created =
        collaborationLinkRepository.createLink(
            linkId, nodeId, "inv12345", SharePermission.READ_AND_WRITE);

    assertThat(created.getId()).isEqualTo(linkId);
    assertThat(created.getNodeId()).isEqualTo(nodeId);
    assertThat(created.getInvitationId()).isEqualTo("inv12345");
    assertThat(created.getPermissions()).isEqualTo(SharePermission.READ_AND_WRITE);

    Optional<CollaborationLink> found = collaborationLinkRepository.getLinkById(linkId);
    assertThat(found).isPresent();
    assertThat(found.get().getInvitationId()).isEqualTo("inv12345");
  }

  @Test
  @TestTransaction
  void getLinkByIdShouldReturnEmptyWhenMissing() {
    assertThat(collaborationLinkRepository.getLinkById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  void getLinkByInvitationIdShouldFindTheMatchingLink() {
    String nodeId = "22222222-2222-2222-2222-222222222222";
    persistParentNode(nodeId);
    UUID linkId = UUID.randomUUID();
    collaborationLinkRepository.createLink(
        linkId, nodeId, "abcdefgh", SharePermission.READ_ONLY);

    Optional<CollaborationLink> found =
        collaborationLinkRepository.getLinkByInvitationId("abcdefgh");

    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(linkId);
  }

  @Test
  @TestTransaction
  void getLinksByNodeIdShouldReturnOnlyLinksOfThatNode() {
    String nodeId = "22222222-2222-2222-2222-222222222223";
    String otherNodeId = "22222222-2222-2222-2222-222222222224";
    persistParentNode(nodeId);
    persistParentNode(otherNodeId);

    UUID link1 = UUID.randomUUID();
    UUID link2 = UUID.randomUUID();
    collaborationLinkRepository.createLink(link1, nodeId, "invite01", SharePermission.READ_ONLY);
    collaborationLinkRepository.createLink(link2, nodeId, "invite02", SharePermission.READ_ONLY);
    collaborationLinkRepository.createLink(
        UUID.randomUUID(), otherNodeId, "invite03", SharePermission.READ_ONLY);

    List<UUID> ids =
        collaborationLinkRepository
            .getLinksByNodeId(nodeId)
            .map(CollaborationLink::getId)
            .toList();

    assertThat(ids).containsExactlyInAnyOrder(link1, link2);
  }

  @Test
  @TestTransaction
  void deleteLinksShouldRemoveOnlyTheGivenIdentifiers() {
    String nodeId = "22222222-2222-2222-2222-222222222225";
    persistParentNode(nodeId);

    UUID toDelete = UUID.randomUUID();
    UUID toKeep = UUID.randomUUID();
    collaborationLinkRepository.createLink(
        toDelete, nodeId, "delete01", SharePermission.READ_ONLY);
    collaborationLinkRepository.createLink(toKeep, nodeId, "keep0001", SharePermission.READ_ONLY);

    collaborationLinkRepository.deleteLinks(List.of(toDelete));

    assertThat(collaborationLinkRepository.getLinkById(toDelete)).isEmpty();
    assertThat(collaborationLinkRepository.getLinkById(toKeep)).isPresent();
  }
}
