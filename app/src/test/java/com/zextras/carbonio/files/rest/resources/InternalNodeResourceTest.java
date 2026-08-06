// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.datafetchers.LinkDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.NodeDataFetcher;
import com.zextras.carbonio.files.rest.types.internal.CreateFolderRequest;
import com.zextras.carbonio.files.rest.types.internal.CreatePublicLinkRequest;
import com.zextras.carbonio.files.rest.types.internal.DeleteAllRequest;
import com.zextras.carbonio.files.rest.types.internal.DeleteAllResponse;
import com.zextras.carbonio.files.rest.types.internal.InternalNodeDto;
import com.zextras.carbonio.files.rest.types.internal.InternalNodeIdDto;
import com.zextras.carbonio.files.rest.types.internal.PublicLinkDto;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.util.List;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link InternalNodeResource}: every
 * dependency is mocked, so these tests exercise only the resource's own delegation/assembly/status
 * -mapping logic, without a container, since the REST beans are reused as-is, not re-derived.
 */
class InternalNodeResourceTest {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String NODE_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String PARENT_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";

  private NodeRepository nodeRepository;
  private UserRepository userRepository;
  private PermissionsChecker permissionsChecker;
  private NodeDataFetcher nodeDataFetcher;
  private LinkDataFetcher linkDataFetcher;
  private InternalNodeResource resource;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    userRepository = mock(UserRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);
    nodeDataFetcher = mock(NodeDataFetcher.class);
    linkDataFetcher = mock(LinkDataFetcher.class);
    resource =
        new InternalNodeResource(
            nodeRepository, userRepository, permissionsChecker, nodeDataFetcher, linkDataFetcher);
  }

  // -------------------------------------------------------------------------------------- getNode

  @Test
  void getNode_returns404WhenNodeNotFound() {
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    RestResponse<InternalNodeDto> response = resource.getNode(USER_ID, NODE_ID);

    assertThat(response.getStatus()).isEqualTo(404);
    verify(permissionsChecker, never()).getPermissions(any(), any());
  }

  @Test
  void getNode_returns403WhenRequesterCannotRead() {
    Node node = mock(Node.class);
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(permissionsChecker.getPermissions(NODE_ID, USER_ID)).thenReturn(ACL.decode(ACL.NONE));

    RestResponse<InternalNodeDto> response = resource.getNode(USER_ID, NODE_ID);

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void getNode_assemblesFolderDto_usingNodeUpdatedAt() {
    Node folder = mock(Node.class);
    when(folder.getId()).thenReturn(NODE_ID);
    when(folder.getName()).thenReturn("My Folder");
    when(folder.getNodeType()).thenReturn(NodeType.FOLDER);
    when(folder.getOwnerId()).thenReturn(OWNER_ID);
    when(folder.getParentId()).thenReturn(Optional.of(PARENT_ID));
    when(folder.getUpdatedAt()).thenReturn(1000L);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folder));
    when(permissionsChecker.getPermissions(NODE_ID, USER_ID)).thenReturn(ACL.decode(ACL.OWNER));

    RestResponse<InternalNodeDto> response = resource.getNode(USER_ID, NODE_ID);

    assertThat(response.getStatus()).isEqualTo(200);
    InternalNodeDto dto = response.getEntity();
    assertThat(dto.id()).isEqualTo(NODE_ID);
    assertThat(dto.name()).isEqualTo("My Folder");
    assertThat(dto.extension()).isNull();
    assertThat(dto.mimeType()).isNull();
    assertThat(dto.size()).isNull();
    assertThat(dto.version()).isNull();
    // No FileVersion exists for a folder: falls back to Node#getUpdatedAt().
    assertThat(dto.updatedAt()).isEqualTo(1000L);
    assertThat(dto.owner().id()).isEqualTo(OWNER_ID);
    assertThat(dto.parent().id()).isEqualTo(PARENT_ID);
    assertThat(dto.permissions().canWriteFile()).isTrue();
  }

  @Test
  void getNode_assemblesFileDto_usingFileVersionUpdatedAt_notNodeUpdatedAt() {
    // CRITICAL behaviour under test: updatedAt must come from the resolved FileVersion, not from
    // Node#getUpdatedAt(), replicating NodeDataFetcher#convertNodeToDataFetcherResult's same-key
    // map override.
    Node file = mock(Node.class);
    when(file.getId()).thenReturn(NODE_ID);
    when(file.getName()).thenReturn("report");
    when(file.getNodeType()).thenReturn(NodeType.TEXT);
    when(file.getOwnerId()).thenReturn(OWNER_ID);
    when(file.getParentId()).thenReturn(Optional.of(PARENT_ID));
    when(file.getUpdatedAt()).thenReturn(1000L); // must be ignored in favour of the FileVersion's
    when(file.getExtension()).thenReturn(Optional.of("pdf"));
    when(file.getCurrentVersion()).thenReturn(2);

    FileVersion versionOne =
        new FileVersion(NODE_ID, "editor-1", 500L, 1, "application/pdf", 111L, "digest1", false);
    FileVersion versionTwo =
        new FileVersion(NODE_ID, "editor-2", 2000L, 2, "application/pdf", 222L, "digest2", false);
    when(file.getFileVersions()).thenReturn(List.of(versionOne, versionTwo));

    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(file));
    when(permissionsChecker.getPermissions(NODE_ID, USER_ID))
        .thenReturn(ACL.decode(SharePermission.READ_ONLY));

    RestResponse<InternalNodeDto> response = resource.getNode(USER_ID, NODE_ID);

    assertThat(response.getStatus()).isEqualTo(200);
    InternalNodeDto dto = response.getEntity();
    assertThat(dto.extension()).isEqualTo("pdf");
    assertThat(dto.mimeType()).isEqualTo("application/pdf");
    assertThat(dto.size()).isEqualTo(222L);
    assertThat(dto.version()).isEqualTo(2);
    // The resolved (current, version 2) FileVersion's timestamp, NOT the node's 1000L.
    assertThat(dto.updatedAt()).isEqualTo(2000L);
    assertThat(dto.permissions().canWriteFile()).isFalse();
  }

  @Test
  void getNode_parentIsNullWhenNodeHasNoParent() {
    Node root = mock(Node.class);
    when(root.getId()).thenReturn(NODE_ID);
    when(root.getName()).thenReturn("ROOT");
    when(root.getNodeType()).thenReturn(NodeType.ROOT);
    when(root.getOwnerId()).thenReturn(OWNER_ID);
    when(root.getParentId()).thenReturn(Optional.empty());
    when(root.getUpdatedAt()).thenReturn(42L);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(root));
    when(permissionsChecker.getPermissions(NODE_ID, USER_ID)).thenReturn(ACL.decode(ACL.OWNER));

    RestResponse<InternalNodeDto> response = resource.getNode(USER_ID, NODE_ID);

    assertThat(response.getEntity().parent()).isNull();
  }

  // ------------------------------------------------------------------------------------
  // createFolder

  @Test
  void createFolder_delegatesToNodeDataFetcher_withATrustedRequester() {
    Node created = mock(Node.class);
    when(created.getId()).thenReturn("new-folder-id");
    when(nodeDataFetcher.createFolder(eq(USER_ID), eq(PARENT_ID), eq("New Folder"), any()))
        .thenReturn(created);

    RestResponse<InternalNodeIdDto> response =
        resource.createFolder(new CreateFolderRequest(USER_ID, PARENT_ID, "New Folder"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity().nodeId()).isEqualTo("new-folder-id");

    ArgumentCaptor<UserMyself> requesterCaptor = ArgumentCaptor.forClass(UserMyself.class);
    verify(nodeDataFetcher)
        .createFolder(eq(USER_ID), eq(PARENT_ID), eq("New Folder"), requesterCaptor.capture());
    assertThat(requesterCaptor.getValue().getId().getUserId()).isEqualTo(USER_ID);
  }

  @Test
  void createFolder_mapsNodeAccessExceptionTo403() {
    when(nodeDataFetcher.createFolder(any(), any(), any(), any()))
        .thenThrow(new NodeDataFetcher.NodeAccessException(PARENT_ID));

    RestResponse<InternalNodeIdDto> response =
        resource.createFolder(new CreateFolderRequest(USER_ID, PARENT_ID, "New Folder"));

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void createFolder_mapsNodeNotFoundExceptionTo404() {
    when(nodeDataFetcher.createFolder(any(), any(), any(), any()))
        .thenThrow(new NodeDataFetcher.NodeNotFoundException(PARENT_ID));

    RestResponse<InternalNodeIdDto> response =
        resource.createFolder(new CreateFolderRequest(USER_ID, PARENT_ID, "New Folder"));

    assertThat(response.getStatus()).isEqualTo(404);
  }

  // --------------------------------------------------------------------------------
  // createPublicLink

  @Test
  void createPublicLink_returns404WhenUserNotFound() {
    when(userRepository.getUserById(null, USER_ID)).thenReturn(Optional.empty());

    RestResponse<PublicLinkDto> response =
        resource.createPublicLink(new CreatePublicLinkRequest(USER_ID, NODE_ID));

    assertThat(response.getStatus()).isEqualTo(404);
    verifyNoInteractions(nodeRepository);
    verifyNoInteractions(linkDataFetcher);
  }

  @Test
  void createPublicLink_returns404WhenNodeNotFound() {
    when(userRepository.getUserById(null, USER_ID))
        .thenReturn(Optional.of(aUser("https://mydomain.example")));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    RestResponse<PublicLinkDto> response =
        resource.createPublicLink(new CreatePublicLinkRequest(USER_ID, NODE_ID));

    assertThat(response.getStatus()).isEqualTo(404);
    verifyNoInteractions(linkDataFetcher);
  }

  @Test
  void createPublicLink_delegatesAndBuildsUrlFromRequesterDomain() {
    when(userRepository.getUserById(null, USER_ID))
        .thenReturn(Optional.of(aUser("https://mydomain.example")));
    Node folderNode = mock(Node.class);
    when(folderNode.getNodeType()).thenReturn(NodeType.FOLDER);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folderNode));

    Link link = new Link("link-id", NODE_ID, "publicId123", 123L, null, null);
    when(linkDataFetcher.createPublicLink(
            USER_ID, NODE_ID, Optional.empty(), Optional.empty(), Optional.empty()))
        .thenReturn(link);
    when(linkDataFetcher.buildPublicLinkUrl(link, "https://mydomain.example", true))
        .thenReturn("https://mydomain.example/files/public/link/access/publicId123");

    RestResponse<PublicLinkDto> response =
        resource.createPublicLink(new CreatePublicLinkRequest(USER_ID, NODE_ID));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity().url())
        .isEqualTo("https://mydomain.example/files/public/link/access/publicId123");
  }

  @Test
  void createPublicLink_mapsLinkLimitReachedExceptionTo429() {
    when(userRepository.getUserById(null, USER_ID))
        .thenReturn(Optional.of(aUser("https://mydomain.example")));
    Node node = mock(Node.class);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkDataFetcher.createPublicLink(any(), any(), any(), any(), any()))
        .thenThrow(new LinkDataFetcher.LinkLimitReachedException(NODE_ID));

    RestResponse<PublicLinkDto> response =
        resource.createPublicLink(new CreatePublicLinkRequest(USER_ID, NODE_ID));

    assertThat(response.getStatus()).isEqualTo(429);
  }

  @Test
  void createPublicLink_mapsNodeAccessExceptionTo403() {
    when(userRepository.getUserById(null, USER_ID))
        .thenReturn(Optional.of(aUser("https://mydomain.example")));
    Node node = mock(Node.class);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkDataFetcher.createPublicLink(any(), any(), any(), any(), any()))
        .thenThrow(new LinkDataFetcher.NodeAccessException(NODE_ID));

    RestResponse<PublicLinkDto> response =
        resource.createPublicLink(new CreatePublicLinkRequest(USER_ID, NODE_ID));

    assertThat(response.getStatus()).isEqualTo(403);
  }

  // ---------------------------------------------------------------------------
  // deleteAllNodesAndBlobs

  @Test
  void deleteAllNodesAndBlobs_delegatesToNodeDataFetcherAndReturnsDeletedTrue() {
    RestResponse<DeleteAllResponse> response =
        resource.deleteAllNodesAndBlobs(new DeleteAllRequest(USER_ID));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity().deleted()).isTrue();
    verify(nodeDataFetcher).deleteAllNodesAndBlobsForUser(USER_ID);
  }

  // --------------------------------------------------------------------------------------- helpers

  private static UserInfo aUser(String domain) {
    return new UserInfo(
        new UserId(USER_ID),
        "user@example.com",
        "A User",
        domain,
        UserStatus.ACTIVE,
        UserType.INTERNAL);
  }
}
