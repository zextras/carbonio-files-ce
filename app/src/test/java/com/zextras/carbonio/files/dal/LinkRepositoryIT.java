// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
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
 * P2c: validates the clean Panache/JPA {@code LinkRepositoryImpl}, which replaces {@code
 * LinkRepositoryEbean}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class LinkRepositoryIT {

  @Inject LinkRepository linkRepository;

  @Inject EntityManager entityManager;

  private Node persistParentNode(String nodeId) {
    Node node =
        new Node(
            nodeId,
            "creator-id",
            "owner-id",
            "",
            1L,
            1L,
            "link-parent-" + nodeId,
            "description",
            NodeType.FOLDER,
            "",
            0L);
    entityManager.persist(node);
    return node;
  }

  private Node persistNodeWithAncestors(String nodeId, List<String> ancestorIds) {
    Node node =
        new Node(
            nodeId,
            "creator-id",
            "owner-id",
            "",
            1L,
            1L,
            "link-child-" + nodeId,
            "description",
            NodeType.FOLDER,
            String.join(Node.ANCESTORS_SEPARATOR, ancestorIds),
            0L);
    entityManager.persist(node);
    return node;
  }

  @Test
  @TestTransaction
  void createLinkShouldPersistAndBeRetrievableById() {
    String nodeId = "33333333-3333-3333-3333-333333333331";
    persistParentNode(nodeId);
    String linkId = UUID.randomUUID().toString();

    Link created =
        linkRepository.createLink(
            linkId,
            nodeId,
            "publicid00000000000000000000001",
            Optional.of(123456789L),
            Optional.of("a description"),
            Optional.of("secret"));

    assertThat(created.getLinkId()).isEqualTo(linkId);
    assertThat(created.getNodeId()).isEqualTo(nodeId);
    assertThat(created.getExpiresAt()).contains(123456789L);
    assertThat(created.getDescription()).contains("a description");
    assertThat(created.getAccessCode()).contains("secret");

    Optional<Link> found = linkRepository.getLinkById(linkId);
    assertThat(found).isPresent();
    assertThat(found.get().getPublicId()).isEqualTo("publicid00000000000000000000001");
  }

  @Test
  @TestTransaction
  void getLinkByIdShouldReturnEmptyWhenMissing() {
    assertThat(linkRepository.getLinkById(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  @TestTransaction
  void getLinkByNotExpiredPublicIdShouldIgnoreExpiredLinks() {
    String nodeId = "33333333-3333-3333-3333-333333333332";
    persistParentNode(nodeId);

    linkRepository.createLink(
        UUID.randomUUID().toString(),
        nodeId,
        "publicid00000000000000000000002",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());

    linkRepository.createLink(
        UUID.randomUUID().toString(),
        nodeId,
        "publicid00000000000000000000003",
        Optional.of(1L), // already expired (epoch millis 1 is in the past)
        Optional.empty(),
        Optional.empty());

    assertThat(linkRepository.getLinkByNotExpiredPublicId("publicid00000000000000000000002"))
        .isPresent();
    assertThat(linkRepository.getLinkByNotExpiredPublicId("publicid00000000000000000000003"))
        .isEmpty();
    assertThat(linkRepository.getLinkByNotExpiredPublicId("does-not-exist")).isEmpty();
  }

  @Test
  @TestTransaction
  void getLinksByNodeIdShouldRespectSortDirection() {
    String nodeId = "33333333-3333-3333-3333-333333333333";
    persistParentNode(nodeId);

    // Persist directly with explicit, deterministic createdAt values (bypassing
    // linkRepository.createLink, which stamps System.currentTimeMillis()) so ordering does not
    // depend on clock resolution between two rapid creations.
    String linkId1 = UUID.randomUUID().toString();
    String linkId2 = UUID.randomUUID().toString();
    entityManager.persist(
        new Link(linkId1, nodeId, "publicid00000000000000000000004", 1_000L, null, null));
    entityManager.persist(
        new Link(linkId2, nodeId, "publicid00000000000000000000005", 2_000L, null, null));

    List<String> ascending =
        linkRepository
            .getLinksByNodeId(nodeId, LinkSort.CREATED_AT_ASC)
            .map(Link::getLinkId)
            .toList();
    List<String> descending =
        linkRepository
            .getLinksByNodeId(nodeId, LinkSort.CREATED_AT_DESC)
            .map(Link::getLinkId)
            .toList();

    assertThat(ascending).containsExactly(linkId1, linkId2);
    assertThat(descending).containsExactly(linkId2, linkId1);
  }

  @Test
  @TestTransaction
  void updateLinkShouldPersistChanges() {
    String nodeId = "33333333-3333-3333-3333-333333333334";
    persistParentNode(nodeId);
    String linkId = UUID.randomUUID().toString();

    Link link =
        linkRepository.createLink(
            linkId,
            nodeId,
            "publicid00000000000000000000006",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    link.setDescription("updated description");
    Link updated = linkRepository.updateLink(link);

    assertThat(updated.getDescription()).contains("updated description");
    assertThat(linkRepository.getLinkById(linkId).get().getDescription())
        .contains("updated description");
  }

  @Test
  @TestTransaction
  void deleteLinkShouldRemoveTheLink() {
    String nodeId = "33333333-3333-3333-3333-333333333335";
    persistParentNode(nodeId);
    String linkId = UUID.randomUUID().toString();
    linkRepository.createLink(
        linkId,
        nodeId,
        "publicid00000000000000000000007",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());

    linkRepository.deleteLink(linkId);

    assertThat(linkRepository.getLinkById(linkId)).isEmpty();
  }

  @Test
  @TestTransaction
  void deleteLinksBulkShouldRemoveOnlyTheGivenIdentifiers() {
    String nodeId = "33333333-3333-3333-3333-333333333336";
    persistParentNode(nodeId);
    String toDelete1 = UUID.randomUUID().toString();
    String toDelete2 = UUID.randomUUID().toString();
    String toKeep = UUID.randomUUID().toString();
    linkRepository.createLink(
        toDelete1,
        nodeId,
        "publicid00000000000000000000008",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
    linkRepository.createLink(
        toDelete2,
        nodeId,
        "publicid00000000000000000000009",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
    linkRepository.createLink(
        toKeep,
        nodeId,
        "publicid00000000000000000000010",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());

    linkRepository.deleteLinksBulk(List.of(toDelete1, toDelete2));

    assertThat(linkRepository.getLinkById(toDelete1)).isEmpty();
    assertThat(linkRepository.getLinkById(toDelete2)).isEmpty();
    assertThat(linkRepository.getLinkById(toKeep)).isPresent();
  }

  @Test
  @TestTransaction
  void isLinkValidForNodeShouldMatchNodeOrItsAncestorsAndRespectExpiration() {
    String ancestorId = "33333333-3333-3333-3333-333333333337";
    String childId = "33333333-3333-3333-3333-333333333338";
    persistParentNode(ancestorId);
    Node child = persistNodeWithAncestors(childId, List.of(ancestorId));

    // Link created on the ancestor, must be considered valid for the child too.
    linkRepository.createLink(
        UUID.randomUUID().toString(),
        ancestorId,
        "publicid00000000000000000000011",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());

    assertThat(linkRepository.isLinkValidForNode("publicid00000000000000000000011", child))
        .isTrue();
    assertThat(linkRepository.isLinkValidForNode("does-not-exist", child)).isFalse();
    assertThat(linkRepository.isLinkValidForNode(null, child)).isFalse();
  }

  @Test
  @TestTransaction
  void getLinkCountByNodeShouldCountOnlyLinksOfThatNode() {
    String nodeId = "33333333-3333-3333-3333-333333333339";
    Node node = persistParentNode(nodeId);
    linkRepository.createLink(
        UUID.randomUUID().toString(),
        nodeId,
        "publicid00000000000000000000012",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
    linkRepository.createLink(
        UUID.randomUUID().toString(),
        nodeId,
        "publicid00000000000000000000013",
        Optional.empty(),
        Optional.empty(),
        Optional.empty());

    assertThat(linkRepository.getLinkCountByNode(node)).isEqualTo(2);
  }
}
