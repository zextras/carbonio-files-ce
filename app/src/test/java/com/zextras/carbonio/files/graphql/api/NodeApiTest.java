// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.model.FileModel;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";

  private NodeRepository nodeRepository;
  private FileVersionRepository fileVersionRepository;
  private PermissionsChecker permissionsChecker;
  private NodeApi nodeApi;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    fileVersionRepository = mock(FileVersionRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);

    UserMyself requester = mock(UserMyself.class);
    UserId userId = new UserId(REQUESTER_ID);
    when(requester.getId()).thenReturn(userId);

    nodeApi = new NodeApi();
    nodeApi.nodeRepository = nodeRepository;
    nodeApi.fileVersionRepository = fileVersionRepository;
    nodeApi.permissionsChecker = permissionsChecker;
    nodeApi.requester = requester;
  }

  private Node mockFileNode(String id) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("testfile");
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    when(node.getNodeCategory()).thenReturn(NodeCategory.FILE);
    when(node.getDescription()).thenReturn(Optional.empty());
    when(node.getParentId()).thenReturn(Optional.of("parent-id"));
    when(node.getOwnerId()).thenReturn("owner-id");
    when(node.getCreatorId()).thenReturn("creator-id");
    when(node.getLastEditorId()).thenReturn(Optional.of("editor-id"));
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getExtension()).thenReturn(Optional.of("txt"));
    when(node.getCurrentVersion()).thenReturn(1);
    when(node.getAncestorsList()).thenReturn(List.of("LOCAL_ROOT"));
    when(node.getCustomAttributes()).thenReturn(Collections.emptyList());

    FileVersion fv = mock(FileVersion.class);
    when(fv.getVersion()).thenReturn(1);
    when(fv.getMimeType()).thenReturn("text/plain");
    when(fv.getSize()).thenReturn(512L);
    when(fv.getDigest()).thenReturn("abc123");
    when(fv.isKeptForever()).thenReturn(false);
    when(fv.getClonedFromVersion()).thenReturn(Optional.empty());
    when(fv.getLastEditorId()).thenReturn("editor-id");
    when(fv.getUpdatedAt()).thenReturn(2000L);

    when(node.getFileVersions()).thenReturn(List.of(fv));
    return node;
  }

  private ACL aclWith(boolean readOnly) {
    return readOnly ? ACL.decode(SharePermission.READ_ONLY) : ACL.decode((short) 0);
  }

  @Test
  void getVersionsReturnsNoVersionsWhenVersionsParamIsAnEmptyList() throws Exception {
    Node node = mockFileNode(NODE_ID);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    List<FileModel> result = nodeApi.getVersions(NODE_ID, List.of());

    assertThat(result).isEmpty();
    verifyNoInteractions(fileVersionRepository);
  }
}
