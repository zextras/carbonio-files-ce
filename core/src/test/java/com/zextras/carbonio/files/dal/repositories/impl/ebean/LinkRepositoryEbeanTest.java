// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import io.ebean.Database;
import io.ebean.Query;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LinkRepositoryEbeanTest {

  private LinkRepository linkRepository;
  private Database ebeanDatabaseMock;

  @BeforeEach
  void setup() {
    ebeanDatabaseMock = Mockito.mock(Database.class, Mockito.RETURNS_DEEP_STUBS);
    EbeanDatabaseManager ebeanDatabaseManagerMock = Mockito.mock(EbeanDatabaseManager.class);
    Mockito.when(ebeanDatabaseManagerMock.getEbeanDatabase()).thenReturn(ebeanDatabaseMock);

    linkRepository = new LinkRepositoryEbean(ebeanDatabaseManagerMock);
  }

  @Test
  void givenAllLinkAttributesTheCreateLinkShouldReturnANewLink() {
    // Given
    final long expirationTimestamp = System.currentTimeMillis();

    // When
    final Link createdLink =
        linkRepository.createLink(
            "00000000-0000-0000-0000-000000000000",
            "11111111-1111-1111-1111-111111111111",
            "1234abcd",
            Optional.of(expirationTimestamp),
            Optional.of("fake description"));

    // Then
    ArgumentCaptor<Link> savedLinkCaptor = ArgumentCaptor.forClass(Link.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).save(savedLinkCaptor.capture());

    Assertions.assertThat(savedLinkCaptor.getValue()).isEqualTo(createdLink);
    Assertions.assertThat(createdLink.getLinkId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(createdLink.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(createdLink.getPublicId()).isEqualTo("1234abcd");
    Assertions.assertThat(createdLink.getExpiresAt()).isPresent().contains(expirationTimestamp);
    Assertions.assertThat(createdLink.getDescription()).isPresent().contains("fake description");
  }

  @Test
  void givenOnlyTheMandatoryLinkAttributesTheCreateLinkShouldReturnANewLink() {
    // Given & When
    final Link createdLink =
        linkRepository.createLink(
            "00000000-0000-0000-0000-000000000000",
            "11111111-1111-1111-1111-111111111111",
            "1234abcd",
            Optional.empty(),
            Optional.empty());

    // Then
    ArgumentCaptor<Link> savedLinkCaptor = ArgumentCaptor.forClass(Link.class);
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).save(savedLinkCaptor.capture());

    Assertions.assertThat(savedLinkCaptor.getValue()).isEqualTo(createdLink);
    Assertions.assertThat(createdLink.getLinkId())
        .isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(createdLink.getNodeId())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    Assertions.assertThat(createdLink.getPublicId()).isEqualTo("1234abcd");
    Assertions.assertThat(createdLink.getExpiresAt()).isEmpty();
    Assertions.assertThat(createdLink.getDescription()).isEmpty();
  }

  @Test
  void givenAnExistingLinkIdTheGetLinkByIdShouldReturnTheRelatedLink() {
    // Given
    Link linkMock = Mockito.mock(Link.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq("id", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(linkMock));

    // When
    Optional<Link> optLink = linkRepository.getLinkById("00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(optLink).isPresent().contains(linkMock);
  }

  @Test
  void givenANotExistingLinkIdTheGetLinkByIdShouldReturnAnOptionalEmpty() {
    // Given
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq(Mockito.eq("id"), Mockito.anyString())
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<Link> optLink = linkRepository.getLinkById("00000000-0000-0000-0000-000000000000");

    // Then
    Assertions.assertThat(optLink).isEmpty();
  }

  @Test
  void givenAnExistingPublicLinkIdTheGetLinkByPublicIdShouldReturnTheRelatedLink() {
    // Given
    Link linkMock = Mockito.mock(Link.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq("public_id", "1234abcd")
                .or()
                .isNull("expire_at")
                .gt(Mockito.eq("expire_at"), Mockito.anyLong())
                .endOr()
                .findOneOrEmpty())
        .thenReturn(Optional.of(linkMock));

    // When
    Optional<Link> optLink = linkRepository.getLinkByNotExpiredPublicId("1234abcd");

    // Then
    Assertions.assertThat(optLink).isPresent().contains(linkMock);
  }

  @Test
  void givenANotExistingLinkPublicIdTheGetLinkByPublicIdShouldReturnAnOptionalEmpty() {
    // Given
    Link linkMock = Mockito.mock(Link.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq(Mockito.eq("public_id"), Mockito.anyString())
                .findOneOrEmpty())
        .thenReturn(Optional.empty());

    // When
    Optional<Link> optLink = linkRepository.getLinkByNotExpiredPublicId("1234abdc");

    // Then
    Assertions.assertThat(optLink).isEmpty();
  }

  @Test
  void
      givenAnExistingNodeIdAndACreatedAtAscSortTheGetLinkByNodeIdShouldReturnAStreamOfAssociatedLinks() {
    // Given
    Link oldestLinkMock = Mockito.mock(Link.class);
    Link youngestLinkMock = Mockito.mock(Link.class);

    Query<Link> queryLinkMock = Mockito.mock(Query.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .query())
        .thenReturn(queryLinkMock);

    Mockito.when(queryLinkMock.order().asc("created_at").findList())
        .thenReturn(Arrays.asList(oldestLinkMock, youngestLinkMock));

    // When
    Stream<Link> links =
        linkRepository.getLinksByNodeId(
            "11111111-1111-1111-1111-111111111111", LinkSort.CREATED_AT_ASC);

    // Then
    Assertions.assertThat(links).hasSize(2).containsExactly(oldestLinkMock, youngestLinkMock);
  }

  @Test
  void
      givenAnExistingNodeIdAndACreatedAtDescSortTheGetLinkByNodeIdShouldReturnAStreamOfAssociatedLinks() {
    // Given
    Link oldestLinkMock = Mockito.mock(Link.class);
    Link youngestLinkMock = Mockito.mock(Link.class);

    Query<Link> queryLinkMock = Mockito.mock(Query.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .query())
        .thenReturn(queryLinkMock);

    Mockito.when(queryLinkMock.order().desc("created_at").findList())
        .thenReturn(Arrays.asList(youngestLinkMock, oldestLinkMock));

    // When
    Stream<Link> links =
        linkRepository.getLinksByNodeId(
            "11111111-1111-1111-1111-111111111111", LinkSort.CREATED_AT_DESC);

    // Then
    Assertions.assertThat(links).hasSize(2).containsExactly(youngestLinkMock, oldestLinkMock);
  }

  @Test
  void givenAnExistingNodeIdWithoutAssociatedLinksTheGetLinkByNodeIdShouldReturnAnEmptyStream() {
    // Given
    Query<Link> queryLinkMock = Mockito.mock(Query.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq("node_id", "11111111-1111-1111-1111-111111111111")
                .query())
        .thenReturn(queryLinkMock);

    Mockito.when(queryLinkMock.order().asc("created_at").findList())
        .thenReturn(Collections.emptyList());

    // When
    Stream<Link> links =
        linkRepository.getLinksByNodeId(
            "11111111-1111-1111-1111-111111111111", LinkSort.CREATED_AT_ASC);

    // Then
    Assertions.assertThat(links).isEmpty();
  }

  @Test
  void givenAnUpdatedLinkTheUpdateLinkShouldSaveTheChangesAndReturnTheUpdatedLink() {
    // Given
    Link updatedLinkMock = Mockito.mock(Link.class);
    Mockito.when(updatedLinkMock.getLinkId()).thenReturn("00000000-0000-0000-0000-000000000000");

    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq("id", "00000000-0000-0000-0000-000000000000")
                .findOneOrEmpty())
        .thenReturn(Optional.of(updatedLinkMock));

    // When
    Link returnedLink = linkRepository.updateLink(updatedLinkMock);

    // Then
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).update(updatedLinkMock);
    Assertions.assertThat(returnedLink).isEqualTo(updatedLinkMock);
  }

  @Test
  void givenAListOfLinkIdTheDeleteLinksShouldDeleteAllTheSpecifiedLinks() {
    // Given
    ArgumentCaptor<String> linkIdsCaptor = ArgumentCaptor.forClass(String.class);
    Mockito.when(
            ebeanDatabaseMock
                .find(Link.class)
                .where()
                .eq(Mockito.eq("id"), linkIdsCaptor.capture())
                .delete())
        .thenReturn(1);

    // When
    linkRepository.deleteLinksBulk(
        Arrays.asList(
            "00000000-0000-0000-0000-000000000000", "11111111-1111-1111-1111-111111111111"));

    // Then
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).beginTransaction();
    List<String> deletedLinkIds = linkIdsCaptor.getAllValues();

    Assertions.assertThat(deletedLinkIds)
        .contains("00000000-0000-0000-0000-000000000000", "11111111-1111-1111-1111-111111111111");
  }

  @Test
  void givenAnEmptyListOfLinkIdTheDeleteLinksShouldDoNothing() {
    // Given & When
    linkRepository.deleteLinksBulk(Collections.emptyList());

    // Then
    Mockito.verify(ebeanDatabaseMock, Mockito.times(1)).beginTransaction();
    Mockito.verifyNoMoreInteractions(ebeanDatabaseMock);
  }
}
