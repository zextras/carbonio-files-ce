// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class CollaborationLinkTest {

  @Test
  void givenAllCollaborationLinkAttributesTheConstructorShouldCreateCollaborationLinkObjectCorrectly() {
    // Given & When
    CollaborationLink collaborationLink = new CollaborationLink(
      UUID.fromString("a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5"),
      "ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c",
      "abcd1234",
      Instant.ofEpochMilli(100L),
      ACL.READ
    );

    // Then
    Assertions
      .assertThat(collaborationLink.getId())
      .isEqualTo(UUID.fromString("a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5"));
    Assertions
      .assertThat(collaborationLink.getNodeId())
      .isEqualTo("ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c");
    Assertions
      .assertThat(collaborationLink.getInvitationId())
      .isEqualTo("abcd1234");
    Assertions
      .assertThat(collaborationLink.getCreatedAt())
      .isEqualTo(Instant.ofEpochMilli(100L));
    Assertions
      .assertThat(collaborationLink.getPermissions())
      .isEqualTo(SharePermission.READ_ONLY);
  }
}
