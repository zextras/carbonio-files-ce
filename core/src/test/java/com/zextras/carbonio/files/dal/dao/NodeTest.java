// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import java.util.Arrays;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.Test;

public class NodeTest {

  @Test
  public void givenAllNodeAttributesTheNodeConstructorShouldCreateNodeObjectCorrectly() {
    // Given && When
    Node node = new Node(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      "e2488edb-f408-4468-bb54-21292edc6440",
      5L,
      10L,
      "test.png",
      "Fake description",
      NodeType.IMAGE,
      "LOCAL_ROOT,e2488edb-f408-4468-bb54-21292edc6440",
      200L
    );

    // Then
    Assertions.assertThat(node.getId()).isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions.assertThat(node.getCreatorId()).isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
    Assertions.assertThat(node.getOwnerId()).isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
    Assertions.assertThat(node.getCreatedAt()).isEqualTo(5L);
    Assertions.assertThat(node.getUpdatedAt()).isEqualTo(10L);
    Assertions.assertThat(node.getFullName()).isEqualTo("test.png");
    Assertions.assertThat(node.getName()).isEqualTo("test");
    Assertions
      .assertThat(node.getExtension())
      .isPresent()
      .isEqualTo(Optional.of("png"));
    Assertions
      .assertThat(node.getDescription())
      .isPresent()
      .isEqualTo(Optional.of("Fake description"));
    Assertions.assertThat(node.getNodeType()).isEqualTo(NodeType.IMAGE);
    Assertions.assertThat(node.getNodeCategory().intValue()).isEqualTo(2);
    Assertions.assertThat(node.getLastEditorId()).isEmpty();
    Assertions
      .assertThat(node.getParentId())
      .isPresent()
      .isEqualTo(Optional.of("e2488edb-f408-4468-bb54-21292edc6440"));
    Assertions
      .assertThat(node.getAncestorIds())
      .isEqualTo("LOCAL_ROOT,e2488edb-f408-4468-bb54-21292edc6440");
    Assertions
      .assertThat(node.getAncestorsList())
      .isNotEmpty()
      .containsAll(Arrays.asList("LOCAL_ROOT", "e2488edb-f408-4468-bb54-21292edc6440"));
    Assertions.assertThat(node.getSize()).isEqualTo(200L);
    Assertions.assertThat(node.getCurrentVersion()).isEqualTo(1);
    Assertions.assertThat(node.getCustomAttributes()).isNull();
    Assertions.assertThat(node.getFileVersions()).isNull();
  }

  @Test
  public void givenOnlyMandatoryNodeAttributesTheNodeConstructorShouldCreateNodeObjectCorrectly() {
    // Given && When
    Node node = new Node(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "",
      "",
      "",
      5L,
      5L,
      "test.png",
      "",
      NodeType.OTHER,
      "",
      0L
    );

    // When
    node
      .setOwnerId("c6bf990d-86b9-49ad-a6c0-12260308b7c5")
      .setParentId("e2488edb-f408-4468-bb54-21292edc6440")
      .setUpdatedAt(10L)
      .setName("changed")
      .setDescription("Fake description")
      .setNodeType(NodeType.IMAGE)
      .setAncestorIds("LOCAL_ROOT,e2488edb-f408-4468-bb54-21292edc6440")
      .setSize(200L)
      .setLastEditorId("c250d362-abf0-4cc6-88c0-8f23e135e655")
      .setCurrentVersion(2);

    // Then
    Assertions.assertThat(node.getId()).isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions.assertThat(node.getCreatorId()).isEqualTo("");
    Assertions.assertThat(node.getOwnerId()).isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
    Assertions.assertThat(node.getCreatedAt()).isEqualTo(5L);
    Assertions.assertThat(node.getUpdatedAt()).isEqualTo(10L);
    Assertions.assertThat(node.getFullName()).isEqualTo("changed.png");
    Assertions.assertThat(node.getName()).isEqualTo("changed");
    Assertions
      .assertThat(node.getExtension())
      .isPresent()
      .isEqualTo(Optional.of("png"));
    Assertions
      .assertThat(node.getDescription())
      .isPresent()
      .isEqualTo(Optional.of("Fake description"));
    Assertions.assertThat(node.getNodeType()).isEqualTo(NodeType.IMAGE);
    Assertions.assertThat(node.getNodeCategory().intValue()).isEqualTo(2);
    Assertions
      .assertThat(node.getLastEditorId())
      .isPresent()
      .isEqualTo(Optional.of("c250d362-abf0-4cc6-88c0-8f23e135e655"));
    Assertions
      .assertThat(node.getParentId())
      .isPresent()
      .isEqualTo(Optional.of("e2488edb-f408-4468-bb54-21292edc6440"));
    Assertions
      .assertThat(node.getAncestorIds())
      .isEqualTo("LOCAL_ROOT,e2488edb-f408-4468-bb54-21292edc6440");
    Assertions
      .assertThat(node.getAncestorsList())
      .isNotEmpty()
      .containsAll(Arrays.asList("LOCAL_ROOT", "e2488edb-f408-4468-bb54-21292edc6440"));
    Assertions.assertThat(node.getSize()).isEqualTo(200L);
    Assertions.assertThat(node.getCurrentVersion()).isEqualTo(2);
    Assertions.assertThat(node.getCustomAttributes()).isNull();
    Assertions.assertThat(node.getFileVersions()).isNull();
  }


  @Test
  public void givenAFolderNodeTypeTheNodeConstructorShouldInitializeTheNodeCategoryAndTheCurrentVersionCorrectly() {
    // Given && When
    Node node = new Node(
      "",
      "",
      "",
      "",
      5L,
      10L,
      "test folder",
      "",
      NodeType.FOLDER,
      "LOCAL_ROOT,e2488edb-f408-4468-bb54-21292edc6440",
      0L
    );

    // Then
    Assertions.assertThat(node.getNodeType()).isEqualTo(NodeType.FOLDER);
    Assertions.assertThat(node.getNodeCategory().intValue()).isEqualTo(1);
    Assertions.assertThat(node.getCurrentVersion()).isEqualTo(1);
  }

  @Test
  public void givenARootNodeTypeTheNodeConstructorShouldInitializeTheNodeCategoryAndTheCurrentVersionCorrectly() {
    // Given && When
    Node node = new Node(
      "",
      "",
      "",
      "",
      5L,
      10L,
      "LOCAL_ROOT",
      "",
      NodeType.ROOT,
      "LOCAL_ROOT",
      0L
    );

    // Then
    Assertions.assertThat(node.getNodeType()).isEqualTo(NodeType.ROOT);
    Assertions.assertThat(node.getNodeCategory().intValue()).isEqualTo(0);
    Assertions.assertThat(node.getCurrentVersion()).isEqualTo(1);
  }

  @Test
  public void givenAFolderNodeTheGetExtensionShouldReturnAnOptionalEmptyAndTheFullNameShouldMatchWithTheName() {
    // Given && When
    Node node = new Node(
      "",
      "",
      "",
      "",
      5L,
      10L,
      "test folder",
      "",
      NodeType.FOLDER,
      "LOCAL_ROOT,e2488edb-f408-4468-bb54-21292edc6440",
      0L
    );

    // Then
    Assertions.assertThat(node.getExtension()).isEmpty();
    Assertions.assertThat(node.getFullName()).isEqualTo(node.getName());
  }

  @Test
  public void givenANodeWithoutAnAncestorIdsTheGetAncestorsListShouldReturnAnEmptyList() {
    // Given && When
    Node node = new Node(
      "",
      "",
      "",
      "",
      5L,
      10L,
      "test folder",
      "",
      NodeType.FOLDER,
      "",
      0L
    );

    // Then
    Assertions.assertThat(node.getAncestorIds()).isEmpty();
    Assertions.assertThat(node.getAncestorsList()).isEmpty();
  }

  @Test
  public void givenANodeIdSurroundedBySpacesAtTheEndTheGetIdShouldReturnItWithoutThem() {
    // Given && When
    Node node = new Node(
      "   868b43cc-3a8f-4c14-a66d-f520d8e7e8bd    ",
      "",
      "",
      "",
      5L,
      10L,
      "test folder",
      "",
      NodeType.FOLDER,
      "",
      0L
    );

    // Then
    Assertions.assertThat(node.getId()).isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
  }
}
