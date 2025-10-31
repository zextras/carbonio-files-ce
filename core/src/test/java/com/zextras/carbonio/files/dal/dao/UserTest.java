// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import com.zextras.carbonio.usermanagement.entities.UserId;
import com.zextras.carbonio.usermanagement.entities.UserMyself;
import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import com.zextras.carbonio.usermanagement.enumerations.UserType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

class UserTest {

  @Test
  void givenUserAttributesUserConstructorShouldCreateUserObjectCorrectly() {
    // Given & When
    UserMyself user =
        new UserMyself(
            new UserId("fake-user-id"),
            "fakeuser@example.com",
            "Fake User",
            "example.com",
            UserStatus.ACTIVE,
            Locale.ENGLISH,
            UserType.INTERNAL,
            Map.of("carbonioFeatureFilesEnabled", "TRUE"));

    // Then
    Assertions.assertThat(user.getId().getUserId()).isEqualTo("fake-user-id");
    Assertions.assertThat(user.getFullName()).isEqualTo("Fake User");
    Assertions.assertThat(user.getEmail()).isEqualTo("fakeuser@example.com");
    Assertions.assertThat(user.getDomain()).isEqualTo("example.com");
    Assertions.assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    Assertions.assertThat(user.getType()).isEqualTo(UserType.INTERNAL);
  }
}
