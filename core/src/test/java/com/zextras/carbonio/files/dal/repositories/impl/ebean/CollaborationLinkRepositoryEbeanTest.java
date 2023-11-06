// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import io.ebean.Database;
import io.ebean.ExpressionList;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class CollaborationLinkRepositoryEbeanTest {

  private CollaborationLinkRepository collaborationLinkRepository;
  private Database ebeanDatabaseMock;
  private Clock clockMock;

  private static Stream<Arguments> collaborationLinksProvider() {
    return Stream.of(
        Arguments.of("8f89bae5-6b92-4be7-bc25-5014094d1a63", Collections.emptyList(), 0),
        Arguments.of(
            "0293d9c6-4600-417b-becc-7f1418141c98",
            Collections.singletonList(Mockito.mock(CollaborationLink.class)),
            1));
  }

  @BeforeEach
  void setup() {
    ebeanDatabaseMock = Mockito.mock(Database.class, Mockito.RETURNS_DEEP_STUBS);
    EbeanDatabaseManager ebeanDatabaseManagerMock = Mockito.mock(EbeanDatabaseManager.class);
    Mockito.when(ebeanDatabaseManagerMock.getEbeanDatabase()).thenReturn(ebeanDatabaseMock);
    clockMock = Mockito.mock(Clock.class);
    collaborationLinkRepository =
        new CollaborationLinkRepositoryEbean(clockMock, ebeanDatabaseManagerMock);
  }

  @Test
  void givenAllCollaborationLinkAttributesTheCreateLinkShouldReturnACollaborationLink() {
    // Given
    Mockito.when(clockMock.instant()).thenReturn(Instant.ofEpochMilli(1));

    // When
    CollaborationLink createdCollaborationLink =
        collaborationLinkRepository.createLink(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            "11111111-1111-1111-1111-111111111111",
            "1234abcd",
            SharePermission.READ_AND_WRITE);

    // Then
    ArgumentCaptor<CollaborationLink> linkCaptor = ArgumentCaptor.forClass(CollaborationLink.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).insert(linkCaptor.capture());

    CollaborationLink savedCollaborationLink = linkCaptor.getValue();

    Assertions.assertThat(createdCollaborationLink).isEqualTo(savedCollaborationLink);
    Assertions.assertThat(createdCollaborationLink.getId())
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    Assertions.assertThat(createdCollaborationLink.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(createdCollaborationLink.getInvitationId()).isEqualTo("1234abcd");
    Assertions.assertThat(createdCollaborationLink.getCreatedAt())
        .isEqualTo(Instant.ofEpochMilli(1));
    Assertions.assertThat(createdCollaborationLink.getPermissions())
        .isEqualTo(SharePermission.READ_AND_WRITE);
  }

  @Test
  void givenAnExistingCollaborationLinkIdTheGetLinkByIdShouldReturnTheRelatedCollaborationLink() {
    // Given
    CollaborationLink collaborationLinkMock = Mockito.mock(CollaborationLink.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(CollaborationLink.class)
                .where()
                .idEq(UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .findOneOrEmpty())
        .thenReturn(Optional.of(collaborationLinkMock));

    // When
    Optional<CollaborationLink> optCollaborationLink =
        collaborationLinkRepository.getLinkById(
            UUID.fromString("00000000-0000-0000-0000-000000000000"));

    // Then
    Assertions.assertThat(optCollaborationLink).isPresent().contains(collaborationLinkMock);
  }

  @Test
  void givenANotExistingCollaborationLinkIdTheGetLinkByIdShouldReturnOptionEmpty() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(CollaborationLink.class)
                .where()
                .idEq(UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<CollaborationLink> optCollaborationLink =
        collaborationLinkRepository.getLinkById(
            UUID.fromString("00000000-0000-0000-0000-000000000000"));

    // Then
    Assertions.assertThat(optCollaborationLink).isNotPresent();
  }

  @Test
  void
      givenAnExistingInvitationIdTheGetLinkByInvitationIdShouldReturnTheRelatedCollaborationLink() {
    // Given
    CollaborationLink collaborationLinkMock = Mockito.mock(CollaborationLink.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(CollaborationLink.class)
                .where()
                .eq("invitation_id", "1234abcd")
                .findOneOrEmpty())
        .thenReturn(Optional.of(collaborationLinkMock));

    // When
    Optional<CollaborationLink> optCollaborationLink =
        collaborationLinkRepository.getLinkByInvitationId("1234abcd");

    // Then
    Assertions.assertThat(optCollaborationLink).isPresent().contains(collaborationLinkMock);
  }

  @Test
  void givenANotExistingInvitationIdTheGetLinkByInvitationIdShouldReturnOptionEmpty() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(CollaborationLink.class)
                .where()
                .eq("invitation_id", "1234abcd")
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<CollaborationLink> optCollaborationLink =
        collaborationLinkRepository.getLinkByInvitationId("1234abcd");

    // Then
    Assertions.assertThat(optCollaborationLink).isNotPresent();
  }

  @ParameterizedTest
  @MethodSource("collaborationLinksProvider")
  void givenAnExistingNodeIdTheGetLinksByNodeIdShouldReturnAStreamOfCollaborationLinks(
      String nodeId, List<CollaborationLink> collaborationLinks, int listSize) {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(CollaborationLink.class)
                .where()
                .eq("node_id", nodeId)
                .findList())
        .thenReturn(collaborationLinks);

    // When
    Stream<CollaborationLink> collaborationLinkStream =
        collaborationLinkRepository.getLinksByNodeId(nodeId);

    // Then
    Assertions.assertThat(collaborationLinkStream).hasSize(listSize);
  }

  @Test
  void givenANotExistingNodeIdTheGetLinkByNodeIdShouldReturnAnEmptyStream() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(CollaborationLink.class)
                .where()
                .eq("node_id", "not-existing-node-id")
                .findList())
        .thenReturn(Collections.emptyList());

    // When
    Stream<CollaborationLink> collaborationLinkStream =
        collaborationLinkRepository.getLinksByNodeId("not-existing-node-id");

    // Then
    Assertions.assertThat(collaborationLinkStream).hasSize(0);
  }

  @Test
  void givenAListOfCollaborationLinkIdTheDeleteLinksShouldDeleteAllTheSpecifiedLinks() {
    // Given
    List<UUID> collaborationLinkIds =
        Arrays.asList(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("0293d9c6-4600-417b-becc-7f1418141c98"));

    ExpressionList<CollaborationLink> expressionListMock = Mockito.mock(ExpressionList.class);
    ArgumentCaptor<List<UUID>> uuidsCaptor = ArgumentCaptor.forClass(List.class);
    Mockito.when(
            ebeanDatabaseMock.find(CollaborationLink.class).where().idIn(uuidsCaptor.capture()))
        .thenReturn(expressionListMock);

    // When
    collaborationLinkRepository.deleteLinks(collaborationLinkIds);

    // Then
    Mockito.verify(expressionListMock, Mockito.times(1)).delete();

    Assertions.assertThat(uuidsCaptor.getValue()).isEqualTo(collaborationLinkIds);
  }

  @Test
  void givenAnEmptyListOfCollaborationLinkIdTheDeleteLinksShouldDoNothing() {
    // Given
    ExpressionList<CollaborationLink> expressionListMock = Mockito.mock(ExpressionList.class);
    ArgumentCaptor<List<UUID>> uuidsCaptor = ArgumentCaptor.forClass(List.class);
    Mockito.when(
            ebeanDatabaseMock.find(CollaborationLink.class).where().idIn(uuidsCaptor.capture()))
        .thenReturn(expressionListMock);

    // When
    collaborationLinkRepository.deleteLinks(Collections.emptyList());

    // Then
    Mockito.verify(expressionListMock, Mockito.times(1)).delete();
    Assertions.assertThat(uuidsCaptor.getValue()).hasSize(0);
  }
}
