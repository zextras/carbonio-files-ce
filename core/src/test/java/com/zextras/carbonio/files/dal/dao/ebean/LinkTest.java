// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class LinkTest {

  @Test
  void givenAllPublicLinkAttributesTheConstructorShouldCreateLinkObjectCorrectly() {
    // Given & When
    Link publicLink = new Link(
      "a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5",
      "ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c",
      "abcd1234",
      5L,
      10L,
      "fake description"
    );

    // Then
    Assertions
      .assertThat(publicLink.getLinkId())
      .isEqualTo("a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5");
    Assertions
      .assertThat(publicLink.getNodeId())
      .isEqualTo("ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c");
    Assertions
      .assertThat(publicLink.getPublicId())
      .isEqualTo("abcd1234");
    Assertions
      .assertThat(publicLink.getCreatedAt())
      .isEqualTo(5L);
    Assertions
      .assertThat(publicLink.getExpiresAt())
      .isPresent()
      .get()
      .isEqualTo(10L);
    Assertions
      .assertThat(publicLink.getDescription())
      .isPresent()
      .get()
      .isEqualTo("fake description");
  }

  @Test
  void givenPartialPublicLinkAttributesTheSettersShouldUpdateLinkObjectCorrectly() {
    // Given
    Link publicLink = new Link(
      "a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5",
      "ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c",
      "abcd1234",
      5L,
      null,
      null
    );

    // When
    publicLink
      .setExpiresAt(10L)
      .setDescription("fake description");

    // Then
    Assertions
      .assertThat(publicLink.getLinkId())
      .isEqualTo("a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5");
    Assertions
      .assertThat(publicLink.getNodeId())
      .isEqualTo("ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c");
    Assertions
      .assertThat(publicLink.getPublicId())
      .isEqualTo("abcd1234");
    Assertions
      .assertThat(publicLink.getCreatedAt())
      .isEqualTo(5L);
    Assertions
      .assertThat(publicLink.getExpiresAt())
      .isPresent()
      .get()
      .isEqualTo(10L);
    Assertions
      .assertThat(publicLink.getDescription())
      .isPresent()
      .get()
      .isEqualTo("fake description");
  }

  @Test
  void givenANullExpiredAtTheGetExpiredAtShouldReturnAnOptionalEmpty() {
    // Given & When
    Link publicLink = new Link(
      "a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5",
      "ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c",
      "abcd1234",
      5L,
      null,
      null
    );

    // Then
    Assertions.assertThat(publicLink.getExpiresAt()).isEmpty();
  }

  @Test
  void givenAnExpirationTimestampEqualsToZeroTheGetExpiredAtShouldReturnAnOptionalEmpty() {
    // Given
    Link publicLink = new Link(
      "a7fd1b7c-9f2c-40e4-83d7-463d4d7ab9c5",
      "ac3da3d3-b1c0-41f1-b0ab-2b6d7328808c",
      "abcd1234",
      5L,
      10L,
      null
    );

    // When
    publicLink.setExpiresAt(0L);

    // Then
    Assertions.assertThat(publicLink.getExpiresAt()).isEmpty();
  }
}
