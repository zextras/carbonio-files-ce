// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.ShareSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.ebean.Database;
import io.ebean.ExpressionList;
import io.ebean.OrderBy;
import io.ebean.Query;
import io.ebean.Transaction;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ShareRepositoryEbeanTest {

  private Database ebeanDatabaseMock;
  private ShareRepository shareRepository;

  @BeforeEach
  void setup() {
    ebeanDatabaseMock = Mockito.mock(Database.class, Mockito.RETURNS_DEEP_STUBS);
    EbeanDatabaseManager ebeanDatabaseManagerMock = Mockito.mock(EbeanDatabaseManager.class);
    Mockito.when(ebeanDatabaseManagerMock.getEbeanDatabase()).thenReturn(ebeanDatabaseMock);

    shareRepository = new ShareRepositoryEbean(ebeanDatabaseManagerMock);
  }

  @Test
  void givenANodeIdWithoutSharesAndAllShareAttributesTheUpsertShareShouldCreateANewShare() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<Share> optShare =
        shareRepository.upsertShare(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_AND_WRITE),
            true,
            false,
            Optional.of(1L));

    // Then
    ArgumentCaptor<Share> createdShareCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).save(createdShareCaptor.capture());

    Assertions.assertThat(optShare).isPresent();
    Share createdShare = optShare.get();

    Assertions.assertThat(createdShare).isEqualTo(createdShareCaptor.getValue());

    Assertions.assertThat(createdShare.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(createdShare.getTargetUserId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(createdShare.getPermissions())
        .isEqualTo(ACL.decode(SharePermission.READ_AND_WRITE));
    Assertions.assertThat(createdShare.isDirect()).isTrue();
    Assertions.assertThat(createdShare.isCreatedViaLink()).isFalse();
    Assertions.assertThat(createdShare.getExpiredAt()).isPresent().contains(1L);
  }

  @Test
  void
      givenANodeIdWithoutSharesAndOnlyTheMandatoryShareAttributesTheUpsertShareShouldCreateANewShare() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<Share> optShare =
        shareRepository.upsertShare(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_ONLY),
            false,
            false,
            Optional.empty());

    // Then
    ArgumentCaptor<Share> createdShareCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).save(createdShareCaptor.capture());

    Assertions.assertThat(optShare).isPresent();
    Share createdShare = optShare.get();

    Assertions.assertThat(createdShare).isEqualTo(createdShareCaptor.getValue());

    Assertions.assertThat(createdShare.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(createdShare.getTargetUserId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(createdShare.getPermissions())
        .isEqualTo(ACL.decode(SharePermission.READ_ONLY));
    Assertions.assertThat(createdShare.isDirect()).isFalse();
    Assertions.assertThat(createdShare.isCreatedViaLink()).isFalse();
    Assertions.assertThat(createdShare.getExpiredAt()).isEmpty();
  }

  @Test
  void
      givenANodeIdAUserIdAlreadySharedToIndirectlyAndAllShareAttributesChangedTheUpsertShareShouldUpdateAndReturnTheExistingShare() {
    // Given
    Share existingShare =
        new Share(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_AND_WRITE),
            1L,
            false,
            false,
            2L);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(existingShare));

    // When
    Optional<Share> optShare =
        shareRepository.upsertShare(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_ONLY),
            true,
            true,
            Optional.of(5L));

    // Then
    ArgumentCaptor<Share> updatedShareCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).update(updatedShareCaptor.capture());

    Assertions.assertThat(optShare).isPresent();
    Share updatedShare = optShare.get();

    Assertions.assertThat(updatedShare).isEqualTo(updatedShareCaptor.getValue());

    Assertions.assertThat(updatedShare.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(updatedShare.getTargetUserId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(updatedShare.getCreatedAt()).isEqualTo(1L);
    Assertions.assertThat(updatedShare.getPermissions())
        .isEqualTo(ACL.decode(SharePermission.READ_ONLY));
    Assertions.assertThat(updatedShare.isDirect()).isTrue();
    Assertions.assertThat(updatedShare.isCreatedViaLink()).isTrue();
    Assertions.assertThat(updatedShare.getExpiredAt()).isPresent().contains(5L);
  }

  @Test
  void
      givenANodeIdAUserIdAlreadySharedToIndirectlyAndNoShareAttributesChangedTheUpsertShareShouldUpdateAndReturnTheExistingShare() {
    // Given
    Share existingShare =
        new Share(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_AND_WRITE),
            1L,
            false,
            false,
            2L);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(existingShare));

    // When
    Optional<Share> optShare =
        shareRepository.upsertShare(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_AND_WRITE),
            false,
            false,
            Optional.empty());

    // Then
    ArgumentCaptor<Share> updatedShareCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).update(updatedShareCaptor.capture());

    Assertions.assertThat(optShare).isPresent();
    Share updatedShare = optShare.get();

    Assertions.assertThat(updatedShare).isEqualTo(updatedShareCaptor.getValue());

    Assertions.assertThat(updatedShare.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(updatedShare.getTargetUserId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(updatedShare.getCreatedAt()).isEqualTo(1L);
    Assertions.assertThat(updatedShare.getPermissions())
        .isEqualTo(ACL.decode(SharePermission.READ_AND_WRITE));
    Assertions.assertThat(updatedShare.isDirect()).isFalse();
    Assertions.assertThat(updatedShare.isCreatedViaLink()).isFalse();
    Assertions.assertThat(updatedShare.getExpiredAt()).isPresent().contains(2L);
  }

  // TODO: this test is checking an odd logic in the upsertShare method (it should be removed when
  // the logic will be simplified)
  @Test
  void
      givenANodeIdAUserIdAlreadySharedToDirectlyAndAllShareAttributesChangedTheUpsertShareShouldNotUpdateTheShareAndReturnOptionalEmpty() {
    // Given
    Share existingShare =
        new Share(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_AND_WRITE),
            1L,
            true,
            false,
            2L);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(existingShare));

    // When
    Optional<Share> optShare =
        shareRepository.upsertShare(
            "11111111-1111-1111-1111-111111111111",
            "00000000-0000-0000-0000-000000000000",
            ACL.decode(SharePermission.READ_ONLY),
            false,
            true,
            Optional.empty());

    // Then
    Mockito.verify(ebeanDatabaseMock, Mockito.times(0)).update(Mockito.any());
    Assertions.assertThat(optShare).isEmpty();
  }

  @Test
  void
      givenAListOfNodeIdsWithoutSharesAndAllShareAttributesTheUpsertShareBulkShouldCreateNewShares() {
    // Given
    Transaction transactionMock = Mockito.mock(Transaction.class);
    Mockito.when(ebeanDatabaseMock.beginTransaction()).thenReturn(transactionMock);
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq(Mockito.eq("node_id"), Mockito.anyString())
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    shareRepository.upsertShareBulk(
        Arrays.asList(
            "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"),
        "00000000-0000-0000-0000-000000000000",
        ACL.decode(SharePermission.READ_AND_WRITE),
        true,
        false,
        Optional.of(1L));

    // Then
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchMode(true);
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchSize(50);
    Mockito.verify(transactionMock, Mockito.times(1)).commit();

    ArgumentCaptor<Share> createdSharesCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(2)).save(createdSharesCaptor.capture());

    List<Share> createdShares = createdSharesCaptor.getAllValues();
    Share firstCreatedShare = createdShares.get(0);
    Share secondCreatedShare = createdShares.get(1);

    Assertions.assertThat(firstCreatedShare.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(secondCreatedShare.getNodeId())
        .isEqualTo("11111111-2222-2222-2222-111111111111");

    Assertions.assertThat(firstCreatedShare.getTargetUserId())
        .isEqualTo(secondCreatedShare.getTargetUserId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(firstCreatedShare.getPermissions())
        .isEqualTo(secondCreatedShare.getPermissions())
        .isEqualTo(ACL.decode(SharePermission.READ_AND_WRITE));
    Assertions.assertThat(firstCreatedShare.isDirect())
        .isEqualTo(secondCreatedShare.isDirect())
        .isTrue();
    Assertions.assertThat(firstCreatedShare.isCreatedViaLink())
        .isEqualTo(secondCreatedShare.isCreatedViaLink())
        .isFalse();
    Assertions.assertThat(firstCreatedShare.getExpiredAt())
        .isEqualTo(secondCreatedShare.getExpiredAt())
        .isPresent()
        .contains(1L);
  }

  @Test
  void givenAnEmptyListOfNodeIdAndAllShareAttributesTheUpsertShareBulkShouldDoNothing() {
    // Given
    Transaction transactionMock = Mockito.mock(Transaction.class);
    Mockito.when(ebeanDatabaseMock.beginTransaction()).thenReturn(transactionMock);

    // When
    shareRepository.upsertShareBulk(
        Collections.emptyList(),
        "00000000-0000-0000-0000-000000000000",
        ACL.decode(SharePermission.READ_AND_WRITE),
        true,
        false,
        Optional.of(1L));

    // Then
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchMode(true);
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchSize(50);
    Mockito.verify(transactionMock, Mockito.times(1)).commit();
    // SKIPMockito.verifyNoMoreInteractions(ebeanDatabaseMock);
  }

  @Test
  void givenAnUpdatedShareTheUpdateShareShouldSaveTheChangesAndReturnTheUpdatedShare() {
    // Given
    Share updatedShareMock = Mockito.mock(Share.class);

    // When
    Share returnedShare = shareRepository.updateShare(updatedShareMock);

    // Then
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).update(updatedShareMock);
    Assertions.assertThat(returnedShare).isEqualTo(updatedShareMock);
  }

  @Test
  void givenANodeIdAndAUserIdSharedToDirectlyTheGetShareShouldReturnTheRelatedShare() {
    // Given
    Share shareMock = Mockito.mock(Share.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(shareMock));

    // When
    Optional<Share> optShare =
        shareRepository.getShare(
            "11111111-1111-1111-1111-111111111111", "00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(optShare).isPresent().contains(shareMock);
  }

  @Test
  void givenANodeIdAndAUserIdNotSharedToTheGetShareShouldReturnAnOptionalEmpty() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq(Mockito.eq("node_id"), Mockito.anyString())
                .eq(Mockito.eq("target_uuid"), Mockito.anyString())
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<Share> optShare =
        shareRepository.getShare(
            "11111111-1111-1111-1111-111111111111", "00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(optShare).isEmpty();
  }

  @Test
  void givenAListOfNodeIdsAndAUserIdSharedToTheGetSharesShouldReturnTheListOfShares() {
    // Given
    Share shareMock1 = Mockito.mock(Share.class);
    Share shareMock2 = Mockito.mock(Share.class);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .in(
                    "node_id",
                    Arrays.asList(
                        "11111111-1111-1111-1111-111111111111",
                        "11111111-2222-2222-2222-111111111111"))
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findList())
        .thenReturn(Arrays.asList(shareMock1, shareMock2));

    // When
    List<Share> shares =
        shareRepository.getShares(
            Arrays.asList(
                "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"),
            "00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(shares).hasSize(2).containsExactly(shareMock1, shareMock2);
  }

  @Test
  void givenAListOfNodeIdsAndAUserIdNotSharedToTheGetSharesShouldReturnAnEmptyOfShares() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .in(
                    "node_id",
                    Arrays.asList(
                        "11111111-1111-1111-1111-111111111111",
                        "11111111-2222-2222-2222-111111111111"))
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findList())
        .thenReturn(Collections.emptyList());

    // When
    List<Share> shares =
        shareRepository.getShares(
            Arrays.asList(
                "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"),
            "00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(shares).isEmpty();
  }

  @Test
  void givenANodeIdAndListOfUserIdsSharedToTheGetSharesShouldReturnTheListOfShares() {
    // Given
    Share shareMock1 = Mockito.mock(Share.class);
    Share shareMock2 = Mockito.mock(Share.class);

    ExpressionList<Share> expressionListShare =
        Mockito.mock(ExpressionList.class, Mockito.RETURNS_DEEP_STUBS);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111"))
        .thenReturn(expressionListShare);

    Mockito.when(expressionListShare.findList()).thenReturn(Arrays.asList(shareMock1, shareMock2));

    // When
    List<Share> shares =
        shareRepository.getShares(
            "11111111-1111-1111-1111-111111111111",
            Arrays.asList(
                "00000000-0000-0000-0000-000000000000", "00000000-1111-1111-1111-000000000000"));

    // Then
    Mockito.verify(expressionListShare, Mockito.times(1))
        .in(
            "target_uuid",
            Arrays.asList(
                "00000000-0000-0000-0000-000000000000", "00000000-1111-1111-1111-000000000000"));

    Assertions.assertThat(shares).hasSize(2).containsExactly(shareMock1, shareMock2);
  }

  @Test
  void givenANodeIdAndAnEmptyListOfUserIdsTheGetSharesShouldReturnAnEmptyListOfShares() {
    // Given
    ExpressionList<Share> expressionListShare =
        Mockito.mock(ExpressionList.class, Mockito.RETURNS_DEEP_STUBS);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111"))
        .thenReturn(expressionListShare);

    Mockito.when(expressionListShare.findList()).thenReturn(Collections.emptyList());

    // When
    List<Share> shares =
        shareRepository.getShares("11111111-1111-1111-1111-111111111111", Collections.emptyList());

    // Then
    Mockito.verify(expressionListShare, Mockito.times(0))
        .in(Mockito.eq("target_uuid"), Mockito.anyList());

    Assertions.assertThat(shares).isEmpty();
  }

  @Test
  void givenAListOfNodeIdsWithTwoSharesTheGetSharesShouldReturnTheListOfShares() {
    // Given
    Share shareMock1 = Mockito.mock(Share.class);
    Share shareMock2 = Mockito.mock(Share.class);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .in(
                    "node_id",
                    Arrays.asList(
                        "11111111-1111-1111-1111-111111111111",
                        "11111111-2222-2222-2222-111111111111"))
                .findList())
        .thenReturn(Arrays.asList(shareMock1, shareMock2));

    // When
    List<Share> shares =
        shareRepository.getShares(
            Arrays.asList(
                "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"));

    // Then
    Assertions.assertThat(shares).hasSize(2).containsExactly(shareMock1, shareMock2);
  }

  @Test
  void givenAListOfNodeIdsWithoutAShareTheGetSharesShouldReturnAnEmptyListOfShares() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .in(Mockito.eq("node_id"), Mockito.anyList())
                .findList())
        .thenReturn(Collections.emptyList());

    // When
    List<Share> shares = shareRepository.getShares(Collections.emptyList());

    // Then
    Assertions.assertThat(shares).isEmpty();
  }

  @Test
  void
      givenANodeIdWithTwoSharesAndNoSortTheGetSharesUsersIdsShouldReturnTheListOfUserIdsSharedTo() {
    // Given
    Share shareMock1 = Mockito.mock(Share.class);
    Mockito.when(shareMock1.getTargetUserId()).thenReturn("00000000-0000-0000-0000-000000000000");

    Share shareMock2 = Mockito.mock(Share.class);
    Mockito.when(shareMock2.getTargetUserId()).thenReturn("00000000-1111-1111-1111-000000000000");

    Query<Share> queryMock = Mockito.mock(Query.class);
    Mockito.when(
            ebeanDatabaseMock
                .createQuery(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .query())
        .thenReturn(queryMock);

    Mockito.when(queryMock.findList()).thenReturn(Arrays.asList(shareMock1, shareMock2));

    // When
    List<String> userIds =
        shareRepository.getSharesUsersIds(
            "11111111-1111-1111-1111-111111111111", Collections.emptyList());

    // Then
    Assertions.assertThat(userIds)
        .hasSize(2)
        .containsExactly(
            "00000000-0000-0000-0000-000000000000", "00000000-1111-1111-1111-000000000000");
    Mockito.verify(queryMock, Mockito.times(0)).order();
  }

  @Test
  void
      givenANodeIdWithTwoSharesAndTheTargetUserAscSortTheGetSharesUsersIdsShouldReturnTheSortedListOfUserIdsSharedTo() {
    // Given
    Share shareMock1 = Mockito.mock(Share.class);
    Mockito.when(shareMock1.getTargetUserId()).thenReturn("00000000-0000-0000-0000-000000000000");

    Share shareMock2 = Mockito.mock(Share.class);
    Mockito.when(shareMock2.getTargetUserId()).thenReturn("00000000-1111-1111-1111-000000000000");

    Query<Share> queryMock = Mockito.mock(Query.class);
    OrderBy<Share> orderByMock = Mockito.mock(OrderBy.class);

    Mockito.when(
            ebeanDatabaseMock
                .createQuery(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .query())
        .thenReturn(queryMock);

    Mockito.when(queryMock.order()).thenReturn(orderByMock);
    Mockito.when(queryMock.findList()).thenReturn(Arrays.asList(shareMock1, shareMock2));

    // When
    List<String> userIds =
        shareRepository.getSharesUsersIds(
            "11111111-1111-1111-1111-111111111111",
            Collections.singletonList(ShareSort.TARGET_USER_ASC));

    // Then
    Assertions.assertThat(userIds)
        .hasSize(2)
        .containsExactly(
            "00000000-0000-0000-0000-000000000000", "00000000-1111-1111-1111-000000000000");
    Mockito.verify(orderByMock, Mockito.times(1)).asc("target_uuid");
  }

  @Test
  void givenANodeIdWithoutSharesTheGetSharesUsersIdsShouldReturnAnEmptyListOfUserIds() {
    // Given
    Query<Share> queryMock = Mockito.mock(Query.class);
    Mockito.when(
            ebeanDatabaseMock
                .createQuery(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .query())
        .thenReturn(queryMock);

    Mockito.when(queryMock.findList()).thenReturn(Collections.emptyList());

    // When
    List<String> userIds =
        shareRepository.getSharesUsersIds(
            "11111111-1111-1111-1111-111111111111", Collections.emptyList());

    // Then
    Assertions.assertThat(userIds).isEmpty();
    Mockito.verify(queryMock, Mockito.times(0)).order();
  }

  @Test
  void givenANodeIdAndAUserIdSharedToTheDeleteShareShouldDeleteTheShareAndReturnTrue() {
    // Given
    Share shareMock = Mockito.mock(Share.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(shareMock));

    Mockito.when(ebeanDatabaseMock.delete(shareMock)).thenReturn(true);

    // When
    boolean isShareDeleted =
        shareRepository.deleteShare(
            "11111111-1111-1111-1111-111111111111", "00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(isShareDeleted).isTrue();

    ArgumentCaptor<Share> deletedShareCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).delete(deletedShareCaptor.capture());

    Assertions.assertThat(shareMock).isEqualTo(deletedShareCaptor.getValue());
  }

  @Test
  void givenANodeIdAndAUserIdNotSharedToTheDeleteShareShouldReturnFalse() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    boolean isShareDeleted =
        shareRepository.deleteShare(
            "11111111-1111-1111-1111-111111111111", "00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(isShareDeleted).isFalse();
    Mockito.verify(ebeanDatabaseMock, Mockito.times(0)).delete(Mockito.any(Share.class));
  }

  @Test
  void givenAListOfNodeIdsAndAUserIdSharedToTheDeleteSharesBulkShouldDeleteTheRelatedShares() {
    // Given
    Transaction transactionMock = Mockito.mock(Transaction.class);
    Mockito.when(ebeanDatabaseMock.beginTransaction()).thenReturn(transactionMock);

    Share shareMock1 = Mockito.mock(Share.class);
    Share shareMock2 = Mockito.mock(Share.class);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(shareMock1));

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq("node_id", "11111111-2222-2222-2222-111111111111")
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(shareMock2));

    // When
    shareRepository.deleteSharesBulk(
        Arrays.asList(
            "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"),
        "00000000-0000-0000-0000-000000000000");

    // Then
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchMode(true);
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchSize(50);
    Mockito.verify(transactionMock, Mockito.times(1)).commit();

    ArgumentCaptor<Share> deletedSharesCaptor = ArgumentCaptor.forClass(Share.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(2)).delete(deletedSharesCaptor.capture());

    List<Share> deletedShares = deletedSharesCaptor.getAllValues();
    Share firstDeletedShare = deletedShares.get(0);
    Share secondDeletedShare = deletedShares.get(1);

    Assertions.assertThat(firstDeletedShare).isEqualTo(shareMock1);
    Assertions.assertThat(secondDeletedShare).isEqualTo(shareMock2);
  }

  @Test
  void givenAListOfNodeIdsAndAUserIdNotSharedToTheDeleteSharesBulkShouldDoNothing() {
    // Given
    Transaction transactionMock = Mockito.mock(Transaction.class);
    Mockito.when(ebeanDatabaseMock.beginTransaction()).thenReturn(transactionMock);

    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .eq(Mockito.eq("node_id"), Mockito.anyString())
                .eq("target_uuid", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    shareRepository.deleteSharesBulk(
        Arrays.asList(
            "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"),
        "00000000-0000-0000-0000-000000000000");

    // Then
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchMode(true);
    Mockito.verify(transactionMock, Mockito.times(1)).setBatchSize(50);
    Mockito.verify(transactionMock, Mockito.times(1)).commit();

    Mockito.verify(ebeanDatabaseMock, Mockito.times(0)).delete(Mockito.any(Share.class));
  }

  @Test
  void givenAListOfNodeIdsSharedToTheDeleteSharesBulkShouldDeleteTheRelatedShares() {
    // Given
    ExpressionList<Share> expressionListMock = Mockito.mock(ExpressionList.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Share.class)
                .where()
                .in(
                    "node_id",
                    Arrays.asList(
                        "11111111-1111-1111-1111-111111111111",
                        "11111111-2222-2222-2222-111111111111")))
        .thenReturn(expressionListMock);

    // When
    shareRepository.deleteSharesBulk(
        Arrays.asList(
            "11111111-1111-1111-1111-111111111111", "11111111-2222-2222-2222-111111111111"));

    // Then
    Mockito.verify(expressionListMock, Mockito.times(1)).delete();
  }

  @Test
  void givenAListOfNodeIdsNotSharedToTheDeleteSharesBulkShouldTryToDeleteZeroShares() {
    // Given
    ExpressionList<Share> expressionListMock = Mockito.mock(ExpressionList.class);
    Mockito.when(ebeanDatabaseMock.find(Share.class).where().in("node_id", Collections.emptyList()))
        .thenReturn(expressionListMock);

    // When
    shareRepository.deleteSharesBulk(Collections.emptyList());

    // Then
    Mockito.verify(expressionListMock, Mockito.times(1)).delete();
  }
}
