// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.graphql.errors.CopyFailureClassifier;
import com.zextras.carbonio.files.graphql.datafetchers.NodeDataFetcher.NodeAccessException;
import com.zextras.carbonio.files.graphql.datafetchers.NodeDataFetcher.NodeNotFoundException;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link NodeDataFetcher#createFolder}, the
 * GraphQL-free bean method extracted from {@code createFolderFetcher} so that both the GraphQL
 * {@code createFolder} mutation and a future {@code /internal/folders} REST endpoint can call the
 * same folder-creation business logic. Mirrors the extraction pattern already used by {@link
 * LinkDataFetcher#createPublicLink} and {@link NodeDataFetcher#deleteAllNodesAndBlobsForUser}.
 *
 * <p>The existing GraphQL-level regression guard for this logic is the acceptance test {@code
 * com.zextras.carbonio.files.acceptance.CreateFolderApiIT}, which continues to exercise {@code
 * createFolderFetcher()} end-to-end and must remain green after this extraction.
 */
class NodeDataFetcherCreateFolderTest {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String PARENT_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  private NodeRepository nodeRepository;
  private NotificationRepository notificationRepository;
  private PermissionsChecker permissionsChecker;
  private ShareRepository shareRepository;
  private FilesConfig filesConfig;
  private NodeDataFetcher nodeDataFetcher;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    notificationRepository = mock(NotificationRepository.class);
    FileVersionRepository fileVersionRepository = mock(FileVersionRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);
    shareRepository = mock(ShareRepository.class);
    ShareDataFetcher shareDataFetcher = mock(ShareDataFetcher.class);
    filesConfig = mock(FilesConfig.class);
    Filestore fileStore = mock(Filestore.class);
    TombstoneRepository tombstoneRepository = mock(TombstoneRepository.class);
    CopyFailureClassifier copyFailureClassifier = mock(CopyFailureClassifier.class);

    nodeDataFetcher =
        new NodeDataFetcher(
            nodeRepository,
            notificationRepository,
            fileVersionRepository,
            permissionsChecker,
            shareRepository,
            shareDataFetcher,
            filesConfig,
            fileStore,
            tombstoneRepository,
            copyFailureClassifier);

    // No inherited shares to propagate unless a test overrides this.
    when(shareRepository.getShares(anyString(), anyList())).thenReturn(Collections.emptyList());
    // No sibling name collision unless a test overrides this.
    when(nodeRepository.getNodeByName(anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    // Echo back whatever createNewNode is asked to create, so the returned Node reflects the
    // arguments actually passed by createFolder (captured below via ArgumentCaptor for assertions
    // that need to inspect them individually).
    when(nodeRepository.createNewNode(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq(NodeType.FOLDER),
            anyString(),
            any()))
        .thenAnswer(
            invocation ->
                new Node(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    1L,
                    1L,
                    invocation.getArgument(4),
                    (String) invocation.getArgument(5),
                    invocation.getArgument(6),
                    invocation.getArgument(7),
                    invocation.getArgument(8)));
  }

  private static UserMyself requester(String userId) {
    return new UserMyself(
        new UserId(userId),
        "requester@test.com",
        "Requester Name",
        "test.com",
        UserStatus.ACTIVE,
        Locale.ENGLISH,
        UserType.INTERNAL,
        Collections.emptyMap());
  }

  private static Node folder(String id, String ownerId, String ancestorIds) {
    return new Node(
        id, ownerId, ownerId, "root", 1L, 1L, "some-folder", null, NodeType.FOLDER, ancestorIds, 0L);
  }

  private static Node root(String id) {
    return new Node(id, id, id, null, 1L, 1L, "ROOT", null, NodeType.ROOT, null, 0L);
  }

  private void grantWritePermission() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(SharePermission.READ_AND_WRITE));
  }

  @Test
  void createFolderCreatesFolderNodeWithRequesterAsOwnerWhenRequesterOwnsParent() {
    grantWritePermission();
    Node parent = folder(PARENT_ID, REQUESTER_ID, "root-id");
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(parent));

    Node created =
        nodeDataFetcher.createFolder(REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID));

    assertThat(created.getNodeType()).isEqualTo(NodeType.FOLDER);
    assertThat(created.getOwnerId()).isEqualTo(REQUESTER_ID);
    assertThat(created.getCreatorId()).isEqualTo(REQUESTER_ID);
    assertThat(created.getAncestorIds()).isEqualTo("root-id," + PARENT_ID);

    ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
    verify(nodeRepository)
        .createNewNode(
            anyString(),
            eq(REQUESTER_ID),
            ownerCaptor.capture(),
            eq(PARENT_ID),
            eq("New Folder"),
            eq(""),
            eq(NodeType.FOLDER),
            eq("root-id," + PARENT_ID),
            eq(0L));
    assertThat(ownerCaptor.getValue()).isEqualTo(REQUESTER_ID);

    // Requester owns the parent, so nobody needs to be notified.
    verify(notificationRepository, never())
        .createAddedNodeNotification(any(), any(), any(), any(), anyList());
  }

  @Test
  void createFolderUsesParentOwnerAsOwnerAndNotifiesWhenRequesterDoesNotOwnParent() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(SharePermission.READ_AND_WRITE));
    Node parent = folder(PARENT_ID, OTHER_USER_ID, "root-id");
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(parent));
    when(filesConfig.areNotificationsEnabled()).thenReturn(true);

    Node created =
        nodeDataFetcher.createFolder(REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID));

    assertThat(created.getOwnerId()).isEqualTo(OTHER_USER_ID);

    verify(nodeRepository)
        .createNewNode(
            anyString(),
            eq(REQUESTER_ID),
            eq(OTHER_USER_ID),
            eq(PARENT_ID),
            eq("New Folder"),
            eq(""),
            eq(NodeType.FOLDER),
            eq("root-id," + PARENT_ID),
            eq(0L));

    // Requester != parent owner and notifications are enabled -> the parent owner is notified.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> usersToNotifyCaptor = ArgumentCaptor.forClass(List.class);
    verify(notificationRepository, times(1))
        .createAddedNodeNotification(
            eq(created), eq(parent), any(), any(), usersToNotifyCaptor.capture());
    assertThat(usersToNotifyCaptor.getValue()).containsExactly(OTHER_USER_ID);
  }

  @Test
  void createFolderUnderRootUsesParentIdAsAncestorsAndRequesterAsOwner() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(SharePermission.READ_AND_WRITE));
    Node rootParent = root(PARENT_ID);
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(rootParent));

    Node created =
        nodeDataFetcher.createFolder(REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID));

    assertThat(created.getOwnerId()).isEqualTo(REQUESTER_ID);
    assertThat(created.getAncestorIds()).isEqualTo(PARENT_ID);

    verify(nodeRepository)
        .createNewNode(
            anyString(),
            eq(REQUESTER_ID),
            eq(REQUESTER_ID),
            eq(PARENT_ID),
            eq("New Folder"),
            eq(""),
            eq(NodeType.FOLDER),
            eq(PARENT_ID),
            eq(0L));

    // Root can't be shared and has no meaningful owner to notify.
    verify(notificationRepository, never())
        .createAddedNodeNotification(any(), any(), any(), any(), anyList());
  }

  @Test
  void createFolderThrowsNodeAccessExceptionWhenRequesterLacksWritePermission() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(SharePermission.READ_ONLY));

    assertThatThrownBy(
            () ->
                nodeDataFetcher.createFolder(
                    REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID)))
        .isInstanceOf(NodeAccessException.class);

    verify(nodeRepository, never())
        .createNewNode(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            any());
  }

  @Test
  void createFolderThrowsNodeNotFoundExceptionWhenParentDoesNotExist() {
    grantWritePermission();
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                nodeDataFetcher.createFolder(
                    REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID)))
        .isInstanceOf(NodeNotFoundException.class);

    verify(nodeRepository, never())
        .createNewNode(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyString(),
            any());
  }

  @Test
  void createFolderThrowsNodeNotFoundExceptionWhenParentIsNotAFolderOrRoot() {
    grantWritePermission();
    Node fileParent =
        new Node(
            PARENT_ID,
            REQUESTER_ID,
            REQUESTER_ID,
            "root",
            1L,
            1L,
            "not-a-folder.txt",
            null,
            NodeType.TEXT,
            "root-id",
            10L);
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(fileParent));

    assertThatThrownBy(
            () ->
                nodeDataFetcher.createFolder(
                    REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID)))
        .isInstanceOf(NodeNotFoundException.class);
  }

  @Test
  void createFolderUsesAlternativeNameWhenSiblingWithSameNameAlreadyExists() {
    grantWritePermission();
    Node parent = folder(PARENT_ID, REQUESTER_ID, "root-id");
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(parent));

    Node collidingSibling = folder("existing-id", REQUESTER_ID, "root-id");
    when(nodeRepository.getNodeByName("New Folder", PARENT_ID, REQUESTER_ID))
        .thenReturn(Optional.of(collidingSibling));
    when(nodeRepository.getNodeByName("New Folder (1)", PARENT_ID, REQUESTER_ID))
        .thenReturn(Optional.empty());

    // Leading/trailing whitespace must also be trimmed before the collision search, exactly as
    // the original inline GraphQL logic did.
    nodeDataFetcher.createFolder(REQUESTER_ID, PARENT_ID, "  New Folder  ", requester(REQUESTER_ID));

    verify(nodeRepository)
        .createNewNode(
            anyString(),
            eq(REQUESTER_ID),
            eq(REQUESTER_ID),
            eq(PARENT_ID),
            eq("New Folder (1)"),
            eq(""),
            eq(NodeType.FOLDER),
            anyString(),
            eq(0L));
  }

  @Test
  void createFolderDoesNotNotifyWhenNotificationsAreDisabledEvenIfThereAreUsersToNotify() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(SharePermission.READ_AND_WRITE));
    Node parent = folder(PARENT_ID, OTHER_USER_ID, "root-id");
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(parent));
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    nodeDataFetcher.createFolder(REQUESTER_ID, PARENT_ID, "New Folder", requester(REQUESTER_ID));

    verify(notificationRepository, never())
        .createAddedNodeNotification(any(), any(), any(), any(), anyList());
  }
}
