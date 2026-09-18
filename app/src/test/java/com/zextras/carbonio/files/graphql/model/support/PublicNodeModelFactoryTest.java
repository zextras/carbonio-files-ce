// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.graphql.model.PublicFileModel;
import com.zextras.carbonio.files.graphql.model.PublicFolderModel;
import com.zextras.carbonio.files.graphql.model.PublicNodeModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicNodeModelFactoryTest {

  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";

  @Test
  void givenFolderNode_from_returnsPublicFolderModel() {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(NODE_ID);
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getName()).thenReturn("MyFolder");
    when(node.getNodeCategory()).thenReturn(NodeCategory.FOLDER);
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);

    PublicNodeModel result = PublicNodeModelFactory.from(node);

    assertThat(result).isInstanceOf(PublicFolderModel.class);
    assertThat(result.getId()).isEqualTo(NODE_ID);
    assertThat(result.getCreatedAt()).isEqualTo(1000L);
    assertThat(result.getUpdatedAt()).isEqualTo(2000L);
    assertThat(result.getName()).isEqualTo("MyFolder");
    assertThat(result.getType())
        .isEqualTo(com.zextras.carbonio.files.graphql.model.NodeType.FOLDER);
  }

  @Test
  void givenRootNode_from_returnsPublicFolderModel() {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(NODE_ID);
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getName()).thenReturn("ROOT");
    when(node.getNodeCategory()).thenReturn(NodeCategory.ROOT);
    when(node.getNodeType()).thenReturn(NodeType.ROOT);

    PublicNodeModel result = PublicNodeModelFactory.from(node);

    assertThat(result).isInstanceOf(PublicFolderModel.class);
    assertThat(result.getType()).isEqualTo(com.zextras.carbonio.files.graphql.model.NodeType.ROOT);
  }

  @Test
  void givenFileNode_from_returnsPublicFileModelWithCorrectFields() {
    FileVersion fv = mock(FileVersion.class);
    when(fv.getMimeType()).thenReturn("text/plain");

    Node node = mock(Node.class);
    when(node.getId()).thenReturn(NODE_ID);
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getName()).thenReturn("document.txt");
    when(node.getNodeCategory()).thenReturn(NodeCategory.FILE);
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    when(node.getExtension()).thenReturn(Optional.of("txt"));
    when(node.getSize()).thenReturn(12345L);
    when(node.getFileVersions()).thenReturn(List.of(fv));

    PublicNodeModel result = PublicNodeModelFactory.from(node);

    assertThat(result).isInstanceOf(PublicFileModel.class);
    PublicFileModel fileModel = (PublicFileModel) result;
    assertThat(fileModel.getId()).isEqualTo(NODE_ID);
    assertThat(fileModel.getName()).isEqualTo("document.txt");
    assertThat(fileModel.getExtension()).isEqualTo("txt");
    assertThat(fileModel.getMimeType()).isEqualTo("text/plain");
    assertThat(fileModel.getSize()).isEqualTo(12345.0);
    assertThat(fileModel.getType())
        .isEqualTo(com.zextras.carbonio.files.graphql.model.NodeType.TEXT);
  }

  @Test
  void givenFileNodeWithNoExtension_from_returnsPublicFileModelWithNullExtension() {
    FileVersion fv = mock(FileVersion.class);
    when(fv.getMimeType()).thenReturn("application/octet-stream");

    Node node = mock(Node.class);
    when(node.getId()).thenReturn(NODE_ID);
    when(node.getCreatedAt()).thenReturn(0L);
    when(node.getUpdatedAt()).thenReturn(0L);
    when(node.getName()).thenReturn("binary");
    when(node.getNodeCategory()).thenReturn(NodeCategory.FILE);
    when(node.getNodeType()).thenReturn(NodeType.APPLICATION);
    when(node.getExtension()).thenReturn(Optional.empty());
    when(node.getSize()).thenReturn(0L);
    when(node.getFileVersions()).thenReturn(List.of(fv));

    PublicNodeModel result = PublicNodeModelFactory.from(node);

    assertThat(result).isInstanceOf(PublicFileModel.class);
    assertThat(((PublicFileModel) result).getExtension()).isNull();
  }
}
