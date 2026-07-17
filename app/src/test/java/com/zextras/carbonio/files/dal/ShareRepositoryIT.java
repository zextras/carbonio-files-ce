// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.ShareSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * P2c: validates the clean Panache/JPA {@code ShareRepositoryImpl}, which replaces {@code
 * ShareRepositoryEbean}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ShareRepositoryIT {

  @Inject ShareRepository shareRepository;

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
            "share-parent-" + nodeId,
            "description",
            NodeType.FOLDER,
            "",
            0L);
    entityManager.persist(node);
    return node;
  }

  @Test
  @TestTransaction
  void getShareShouldReturnEmptyWhenMissing() {
    assertThat(shareRepository.getShare("66666666-6666-6666-6666-666666666661", "user-1"))
        .isEmpty();
  }

  @Test
  @TestTransaction
  void upsertShareShouldCreateANewShareWhenMissing() {
    String nodeId = "66666666-6666-6666-6666-666666666662";
    persistParentNode(nodeId);

    Optional<Share> created =
        shareRepository.upsertShare(
            nodeId,
            "user-1",
            ACL.decode(SharePermission.READ_ONLY),
            true,
            false,
            Optional.empty());

    assertThat(created).isPresent();
    assertThat(created.get().getNodeId()).isEqualTo(nodeId);
    assertThat(created.get().getTargetUserId()).isEqualTo("user-1");
    assertThat(created.get().getPermissions().canWrite()).isFalse();
    assertThat(created.get().isDirect()).isTrue();
    assertThat(created.get().isCreatedViaLink()).isFalse();

    assertThat(shareRepository.getShare(nodeId, "user-1")).isPresent();
  }

  @Test
  @TestTransaction
  void upsertShareShouldConvertAnInheritedShareToDirect() {
    String nodeId = "66666666-6666-6666-6666-666666666663";
    persistParentNode(nodeId);
    shareRepository.upsertShare(
        nodeId, "user-1", ACL.decode(SharePermission.READ_ONLY), false, false, Optional.empty());

    Optional<Share> upserted =
        shareRepository.upsertShare(
            nodeId,
            "user-1",
            ACL.decode(SharePermission.READ_AND_WRITE),
            true,
            true,
            Optional.of(999_000L));

    assertThat(upserted).isPresent();
    assertThat(upserted.get().isDirect()).isTrue();
    assertThat(upserted.get().getPermissions().canWrite()).isTrue();
    assertThat(upserted.get().isCreatedViaLink()).isTrue();
    assertThat(upserted.get().getExpiredAt()).contains(999_000L);
  }

  @Test
  @TestTransaction
  void upsertShareShouldReturnEmptyWhenConvertingADirectShareBackToInherited() {
    String nodeId = "66666666-6666-6666-6666-666666666664";
    persistParentNode(nodeId);
    shareRepository.upsertShare(
        nodeId, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());

    Optional<Share> result =
        shareRepository.upsertShare(
            nodeId,
            "user-1",
            ACL.decode(SharePermission.READ_AND_WRITE),
            false,
            false,
            Optional.empty());

    assertThat(result).isEmpty();
  }

  @Test
  @TestTransaction
  void upsertShareBulkShouldCreateSharesOnEveryGivenNode() {
    String nodeId1 = "66666666-6666-6666-6666-666666666665";
    String nodeId2 = "66666666-6666-6666-6666-666666666666";
    persistParentNode(nodeId1);
    persistParentNode(nodeId2);

    shareRepository.upsertShareBulk(
        List.of(nodeId1, nodeId2),
        "user-bulk",
        ACL.decode(SharePermission.READ_ONLY),
        false,
        false,
        Optional.empty());

    assertThat(shareRepository.getShare(nodeId1, "user-bulk")).isPresent();
    assertThat(shareRepository.getShare(nodeId2, "user-bulk")).isPresent();
  }

  @Test
  @TestTransaction
  void updateShareShouldPersistChanges() {
    String nodeId = "66666666-6666-6666-6666-666666666667";
    persistParentNode(nodeId);
    Share share =
        shareRepository
            .upsertShare(
                nodeId,
                "user-1",
                ACL.decode(SharePermission.READ_ONLY),
                true,
                false,
                Optional.empty())
            .orElseThrow();

    share.setPermissions(ACL.decode(SharePermission.READ_WRITE_AND_SHARE));
    shareRepository.updateShare(share);

    Share reloaded = shareRepository.getShare(nodeId, "user-1").orElseThrow();
    assertThat(reloaded.getPermissions().canShare()).isTrue();
  }

  @Test
  @TestTransaction
  void deleteShareShouldRemoveItAndReturnTrue() {
    String nodeId = "66666666-6666-6666-6666-666666666668";
    persistParentNode(nodeId);
    shareRepository.upsertShare(
        nodeId, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());

    boolean deleted = shareRepository.deleteShare(nodeId, "user-1");

    assertThat(deleted).isTrue();
    assertThat(shareRepository.getShare(nodeId, "user-1")).isEmpty();
  }

  @Test
  @TestTransaction
  void deleteShareShouldReturnFalseWhenMissing() {
    String nodeId = "66666666-6666-6666-6666-666666666669";
    persistParentNode(nodeId);

    assertThat(shareRepository.deleteShare(nodeId, "user-does-not-exist")).isFalse();
  }

  @Test
  @TestTransaction
  void deleteSharesBulkByTargetUserShouldRemoveOnlyThatUsersShares() {
    String nodeId1 = "66666666-6666-6666-6666-666666666670";
    String nodeId2 = "66666666-6666-6666-6666-666666666671";
    persistParentNode(nodeId1);
    persistParentNode(nodeId2);
    shareRepository.upsertShare(
        nodeId1, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId2, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId1, "user-2", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());

    shareRepository.deleteSharesBulk(List.of(nodeId1, nodeId2), "user-1");

    assertThat(shareRepository.getShare(nodeId1, "user-1")).isEmpty();
    assertThat(shareRepository.getShare(nodeId2, "user-1")).isEmpty();
    assertThat(shareRepository.getShare(nodeId1, "user-2")).isPresent();
  }

  @Test
  @TestTransaction
  void deleteSharesBulkForAllUsersShouldRemoveEveryShareOfGivenNodes() {
    String nodeId1 = "66666666-6666-6666-6666-666666666672";
    String nodeId2 = "66666666-6666-6666-6666-666666666673";
    String untouchedNodeId = "66666666-6666-6666-6666-666666666674";
    persistParentNode(nodeId1);
    persistParentNode(nodeId2);
    persistParentNode(untouchedNodeId);
    shareRepository.upsertShare(
        nodeId1, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId2, "user-2", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        untouchedNodeId,
        "user-3",
        ACL.decode(SharePermission.READ_ONLY),
        true,
        false,
        Optional.empty());

    shareRepository.deleteSharesBulk(List.of(nodeId1, nodeId2));

    assertThat(shareRepository.getShare(nodeId1, "user-1")).isEmpty();
    assertThat(shareRepository.getShare(nodeId2, "user-2")).isEmpty();
    assertThat(shareRepository.getShare(untouchedNodeId, "user-3")).isPresent();
  }

  @Test
  @TestTransaction
  void getSharesByNodeIdsAndTargetUserShouldFilterByBoth() {
    String nodeId1 = "66666666-6666-6666-6666-666666666675";
    String nodeId2 = "66666666-6666-6666-6666-666666666676";
    persistParentNode(nodeId1);
    persistParentNode(nodeId2);
    shareRepository.upsertShare(
        nodeId1, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId2, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId1, "user-2", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());

    List<Share> shares = shareRepository.getShares(List.of(nodeId1, nodeId2), "user-1");

    assertThat(shares).hasSize(2);
    assertThat(shares).allSatisfy(s -> assertThat(s.getTargetUserId()).isEqualTo("user-1"));
  }

  @Test
  @TestTransaction
  void getSharesByNodeIdAndTargetUsersShouldReturnAllWhenTargetListIsEmpty() {
    String nodeId = "66666666-6666-6666-6666-666666666677";
    persistParentNode(nodeId);
    shareRepository.upsertShare(
        nodeId, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId, "user-2", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());

    List<Share> allShares = shareRepository.getShares(nodeId, List.of());
    List<Share> filteredShares = shareRepository.getShares(nodeId, List.of("user-1"));

    assertThat(allShares).hasSize(2);
    assertThat(filteredShares).extracting(Share::getTargetUserId).containsExactly("user-1");
  }

  @Test
  @TestTransaction
  void getSharesByNodeIdsBatchShouldReturnAllSharesGroupableByNodeId() {
    String nodeId1 = "66666666-6666-6666-6666-666666666678";
    String nodeId2 = "66666666-6666-6666-6666-666666666679";
    persistParentNode(nodeId1);
    persistParentNode(nodeId2);
    shareRepository.upsertShare(
        nodeId1, "user-1", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId1, "user-2", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());
    shareRepository.upsertShare(
        nodeId2, "user-3", ACL.decode(SharePermission.READ_ONLY), true, false, Optional.empty());

    List<Share> shares = shareRepository.getShares(List.of(nodeId1, nodeId2));
    Map<String, List<Share>> groupedByNodeId =
        shares.stream().collect(Collectors.groupingBy(Share::getNodeId));

    assertThat(shares).hasSize(3);
    assertThat(groupedByNodeId.get(nodeId1)).hasSize(2);
    assertThat(groupedByNodeId.get(nodeId2)).hasSize(1);
  }

  @Test
  @TestTransaction
  void getSharesUsersIdsShouldRespectSortDirection() {
    String nodeId = "66666666-6666-6666-6666-666666666680";
    persistParentNode(nodeId);
    entityManager.persist(
        new Share(nodeId, "user-a", ACL.decode(SharePermission.READ_ONLY), 1_000L, true, false, null));
    entityManager.persist(
        new Share(nodeId, "user-b", ACL.decode(SharePermission.READ_ONLY), 2_000L, true, false, null));

    List<String> ascending =
        shareRepository.getSharesUsersIds(nodeId, List.of(ShareSort.CREATION_ASC));
    List<String> descending =
        shareRepository.getSharesUsersIds(nodeId, List.of(ShareSort.CREATION_DESC));

    assertThat(ascending).containsExactly("user-a", "user-b");
    assertThat(descending).containsExactly("user-b", "user-a");
  }
}
