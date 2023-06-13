// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.dal.EbeanWithInMemoryDatabase;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class NodeCustomAttributesTest {

  @Test
  void givenAllNodeCustomAttributesTheConstructorShouldCreateNodeCustomAttributeObjectCorrectly() {
    // Given & When
    NodeCustomAttributes nodeCustomAttributes = new NodeCustomAttributes(
      "56a6a78c-d8b5-447b-847a-e2c3a1c69f35",
      "90d9fb09-bbe6-4441-b09e-f8e17db7baef",
      true
    );

    Assertions
      .assertThat(nodeCustomAttributes.getNodeId())
      .isEqualTo("56a6a78c-d8b5-447b-847a-e2c3a1c69f35");
    Assertions
      .assertThat(nodeCustomAttributes.getUserId())
      .isEqualTo("90d9fb09-bbe6-4441-b09e-f8e17db7baef");
    Assertions.assertThat(nodeCustomAttributes.getFlag()).isTrue();
    Assertions.assertThat(nodeCustomAttributes.getColor()).isNull();
    Assertions.assertThat(nodeCustomAttributes.getExtra()).isEmpty();
  }

  @Test
  void givenAnOppositeNodeFlagAttributeTheSetFlagShouldSetTheAttributeCorrectly() {
    try (EbeanWithInMemoryDatabase database = new EbeanWithInMemoryDatabase()) {

      // Given
      Node node = new Node(
        "56a6a78c-d8b5-447b-847a-e2c3a1c69f35",
        "90d9fb09-bbe6-4441-b09e-f8e17db7baef",
        "90d9fb09-bbe6-4441-b09e-f8e17db7baef",
        "LOCAL_ROOT",
        0L,
        0L,
        "node",
        "",
        NodeType.TEXT,
        "",
        0L
      );
      database.getDatabase().insert(node);

      NodeCustomAttributes nodeCustomAttributes = new NodeCustomAttributes(
        "56a6a78c-d8b5-447b-847a-e2c3a1c69f35",
        "90d9fb09-bbe6-4441-b09e-f8e17db7baef",
        true
      );
      nodeCustomAttributes.save();

      // When
      nodeCustomAttributes.setFlag(false);

      // Then
      Assertions.assertThat(nodeCustomAttributes.getFlag()).isFalse();
    }
  }

  @Test
  void givenNodeIdAndVersionTheFileVersionPKConstructorShouldCreateFileVersionPKObjectCorrectly() {
    // Given & When
    NodeCustomAttributesPK nodeCustomAttributesPrimaryKey = new NodeCustomAttributesPK(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "90d9fb09-bbe6-4441-b09e-f8e17db7baef"
    );

    // Then
    Assertions
      .assertThat(nodeCustomAttributesPrimaryKey.getNodeId())
      .isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions
      .assertThat(nodeCustomAttributesPrimaryKey.getUserId())
      .isEqualTo("90d9fb09-bbe6-4441-b09e-f8e17db7baef");
  }
}
