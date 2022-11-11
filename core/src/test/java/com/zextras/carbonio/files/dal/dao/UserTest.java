// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserTest {

  @Test
  public void userConstructorCreatesUserObjectCorrectly() {
    User user = new User(
      "fake-user-id",
      "Fake User",
      "fakeuser@example.com",
      "example.com"
    );

    Assertions
      .assertThat(user.getUuid())
      .isEqualTo("fake-user-id");

    Assertions
      .assertThat(user.getFullName())
      .isEqualTo("Fake User");

    Assertions
      .assertThat(user.getEmail())
      .isEqualTo("fakeuser@example.com");

    Assertions
      .assertThat(user.getDomain())
      .isEqualTo("example.com");
  }
}
