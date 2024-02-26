// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ShareTest {

  @Test
  void givenAllShareAttributesTheConstructorShouldCreateShareObjectCorrectly() {
    // Given & When
    Share share = new Share(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      ACL.decode(ACL.READ),
      5L,
      true,
      true,
      10L
    );

    // Then
    Assertions.assertThat(share.getNodeId()).isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions.assertThat(share.getTargetUserId()).isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
    Assertions.assertThat(share.getPermissions()).isEqualTo(ACL.decode(ACL.READ));
    Assertions.assertThat(share.getCreatedAt()).isEqualTo(5L);
    Assertions.assertThat(share.isDirect()).isTrue();
    Assertions.assertThat(share.isCreatedViaLink()).isTrue();
    Assertions.assertThat(share.getExpiredAt()).contains(10L);
  }

  @Test
  void givenDifferentShareAttributesTheSettersShouldUpdateShareObjectCorrectly() {
    // Given
    Share share = new Share(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      ACL.decode(ACL.SHARE),
      5L,
      true,
      true,
      null
    );

    // When
    share
      .setDirect(false)
      .setExpiredAt(20L)
      .setPermissions(ACL.decode(ACL.OWNER))
      .setCreatedViaLink(false);

    // Then
    Assertions.assertThat(share.getNodeId()).isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions.assertThat(share.getTargetUserId()).isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
    Assertions.assertThat(share.getPermissions()).isEqualTo(ACL.decode(ACL.OWNER));
    Assertions.assertThat(share.getCreatedAt()).isEqualTo(5L);
    Assertions.assertThat(share.isDirect()).isFalse();
    Assertions.assertThat(share.isCreatedViaLink()).isFalse();
    Assertions.assertThat(share.getExpiredAt()).contains(20L);
  }


  @Test
  void givenANullExpiredAtTheGetExpiredAtShouldReturnAnOptionalEmpty() {
    // Given & When
    Share share = new Share(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      ACL.decode(ACL.SHARE),
      5L,
      true,
      true,
      null
    );

    // Then
    Assertions.assertThat(share.getExpiredAt()).isEmpty();
  }

  @Test
  void givenNodeIdAndUserIdTheSharePkConstructorShouldCreateSharePkObjectCorrectly() {
    // Given & When
    NodeCustomAttributesPK nodeCustomAttributesPK = new NodeCustomAttributesPK(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5"
    );

    // Then
    Assertions
      .assertThat(nodeCustomAttributesPK.getNodeId())
      .isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions
      .assertThat(nodeCustomAttributesPK.getUserId())
      .isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
  }
}
