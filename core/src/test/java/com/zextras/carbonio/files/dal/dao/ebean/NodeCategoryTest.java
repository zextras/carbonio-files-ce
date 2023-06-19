// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NodeCategoryTest {

  private static Stream<Arguments> nodeCategoriesProvider() {
    return Stream.of(
      Arguments.arguments(NodeCategory.ROOT, (short) 0),
      Arguments.arguments(NodeCategory.FOLDER, (short) 1),
      Arguments.arguments(NodeCategory.FILE, (short) 2)
    );
  }

  @ParameterizedTest
  @MethodSource("nodeCategoriesProvider")
  void givenANodeCategoryTheGetValueShouldReturnTheRelatedShortValue(
    NodeCategory category,
    short value
  ) {
    // Given & When
    short nodeCategoryValue = category.getValue();

    // Then
    Assertions.assertThat(nodeCategoryValue).isEqualTo(value);
  }

  @ParameterizedTest
  @MethodSource("nodeCategoriesProvider")
  void givenAValidShortTheDecodeShouldReturnTheRelatedNodeCategory(
    NodeCategory category,
    short value
  ) {
    // Given & When
    NodeCategory nodeCategory = NodeCategory.decode(value);

    // Then
    Assertions.assertThat(nodeCategory).isEqualTo(category);
  }

  @Test
  void givenAnInvalidShortTheDecodeShouldThrowAnIllegalArgumentException() {
    // Given
    short value = 3;

    // When
    ThrowableAssert.ThrowingCallable throwable = () -> NodeCategory.decode(value);

    //Then
    Assertions
      .assertThatIllegalArgumentException()
      .isThrownBy(throwable)
      .withMessage("Invalid value for the NodeCategory enum: 3");
  }
}
