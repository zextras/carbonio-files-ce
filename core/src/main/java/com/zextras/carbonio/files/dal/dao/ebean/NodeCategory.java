// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import java.util.Arrays;

/**
 * Allows to map a category of a {@link Node} to a short value. In the database each node belongs to
 * a category and to save bits each one of them is represented as a short value.
 */
public enum NodeCategory {
  ROOT((short) 0),
  FOLDER((short) 1),
  FILE((short) 2);

  private final short value;

  NodeCategory(short value) {
    this.value = value;
  }

  /**
   * @return the short related to the {@link NodeCategory}.
   */
  public short getValue() {
    return value;
  }

  /**
   * Decodes a short into a {@link NodeCategory}
   *
   * @param value is a <code>short</code> representing the value to convert. If the value is not 0,
   *              1 or 2 then the method throws an {@link IllegalArgumentException}.
   * @return a {@link NodeCategory} representing the short value passed.
   */
  public static NodeCategory decode(short value) {
    return Arrays.stream(values())
      .filter(entry -> entry.value == value)
      .findFirst()
      .orElseThrow(() ->
        new IllegalArgumentException(String.format(
          "Invalid value for the NodeCategory enum: %d",
          value
        ))
      );
  }
}
