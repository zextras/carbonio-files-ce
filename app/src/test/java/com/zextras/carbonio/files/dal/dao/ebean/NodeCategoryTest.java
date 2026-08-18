// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plain JUnit unit test for {@link NodeCategory}'s short-encoding contract — the DB persistence
 * mapping ({@code getValue}/{@code decode}) that no {@code app/src/test} IT or UT covers directly.
 * A {@link Node} is always persisted/read with a valid category (its constructor derives 0/1/2 from
 * the {@link NodeType}), so {@link NodeCategory#decode}'s invalid-value validation branch is
 * unreachable end to end and only assertable at this level.
 */
class NodeCategoryTest {

  private static Stream<Arguments> nodeCategories() {
    return Stream.of(
        Arguments.of(NodeCategory.ROOT, (short) 0),
        Arguments.of(NodeCategory.FOLDER, (short) 1),
        Arguments.of(NodeCategory.FILE, (short) 2));
  }

  @ParameterizedTest
  @MethodSource("nodeCategories")
  void givenANodeCategoryTheGetValueShouldReturnItsShort(NodeCategory category, short value) {
    Assertions.assertThat(category.getValue()).isEqualTo(value);
  }

  @ParameterizedTest
  @MethodSource("nodeCategories")
  void givenAValidShortTheDecodeShouldReturnItsNodeCategory(NodeCategory category, short value) {
    Assertions.assertThat(NodeCategory.decode(value)).isEqualTo(category);
  }

  @Test
  void givenAnInvalidShortTheDecodeShouldThrowAnIllegalArgumentException() {
    Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> NodeCategory.decode((short) 3))
        .withMessage("Invalid value for the NodeCategory enum: 3");
  }
}
