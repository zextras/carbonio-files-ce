// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import com.zextras.carbonio.usermanagement.enumerations.UserType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UserTest {

  @Test
  void givenUserAttributesUserConstructorShouldCreateUserObjectCorrectly() {
    // Given & When
    User user =
        new User(
            "fake-user-id",
            "Fake User",
            "fakeuser@example.com",
            "example.com",
            UserStatus.ACTIVE,
            UserType.INTERNAL);

    // Then
    Assertions.assertThat(user.getId()).isEqualTo("fake-user-id");
    Assertions.assertThat(user.getFullName()).isEqualTo("Fake User");
    Assertions.assertThat(user.getEmail()).isEqualTo("fakeuser@example.com");
    Assertions.assertThat(user.getDomain()).isEqualTo("example.com");
    Assertions.assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    Assertions.assertThat(user.getType()).isEqualTo(UserType.INTERNAL);
  }
}
