// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;


public class ACLTest {

  static Stream<Arguments> aclsToCheckTheirRights() {
    return Stream.of(
      Arguments.arguments(ACL.NONE, false, false, false, false, (short) 0),
      Arguments.arguments(ACL.READ, true, false, false, false, (short) 1),
      Arguments.arguments(ACL.WRITE, false, true, false, true,(short)  2),
      Arguments.arguments(ACL.SHARE, false, false, true, false, (short) 4),
      Arguments.arguments(ACL.OWNER, true, true, true, true, (short) 7),
      Arguments.arguments(
        ACL.SharePermission.READ_ONLY.encode(),
        true,
        false,
        false,
        false,
        (short) 1
      ),
      Arguments.arguments(
        ACL.SharePermission.READ_AND_WRITE.encode(),
        true,
        true,
        false,
        true,
        (short) 3
      ),
      Arguments.arguments(
        ACL.SharePermission.READ_AND_SHARE.encode(),
        true,
        false,
        true,
        false,
        (short) 5
      ),
      Arguments.arguments(
        ACL.SharePermission.READ_WRITE_AND_SHARE.encode(),
        true,
        true,
        true,
        true,
        (short) 7
      )
    );
  }

  static Stream<Arguments> pairOfAclsToCheckIfTheFirstIsContainedInTheOtherOne() {
    return Stream.of(
      Arguments.arguments(ACL.NONE, ACL.SharePermission.READ_ONLY, false),
      Arguments.arguments(ACL.NONE, ACL.SharePermission.READ_AND_WRITE, false),
      Arguments.arguments(ACL.NONE, ACL.SharePermission.READ_AND_SHARE, false),
      Arguments.arguments(ACL.NONE, ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.READ, ACL.SharePermission.READ_ONLY, true),
      Arguments.arguments(ACL.READ, ACL.SharePermission.READ_AND_WRITE, false),
      Arguments.arguments(ACL.READ, ACL.SharePermission.READ_AND_SHARE, false),
      Arguments.arguments(ACL.READ, ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.WRITE, ACL.SharePermission.READ_ONLY, false),
      Arguments.arguments(ACL.WRITE, ACL.SharePermission.READ_AND_WRITE, false),
      Arguments.arguments(ACL.WRITE, ACL.SharePermission.READ_AND_SHARE, false),
      Arguments.arguments(ACL.WRITE, ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.SHARE, ACL.SharePermission.READ_ONLY, false),
      Arguments.arguments(ACL.SHARE, ACL.SharePermission.READ_AND_WRITE, false),
      Arguments.arguments(ACL.SHARE, ACL.SharePermission.READ_AND_SHARE, false),
      Arguments.arguments(ACL.SHARE, ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.OWNER, ACL.SharePermission.READ_ONLY, true),
      Arguments.arguments(ACL.OWNER, ACL.SharePermission.READ_AND_WRITE, true),
      Arguments.arguments(ACL.OWNER, ACL.SharePermission.READ_AND_SHARE, true),
      Arguments.arguments(ACL.OWNER, ACL.SharePermission.READ_WRITE_AND_SHARE, true),
      Arguments.arguments(ACL.SharePermission.READ_ONLY.encode(), ACL.SharePermission.READ_AND_WRITE, false),
      Arguments.arguments(ACL.SharePermission.READ_ONLY.encode(), ACL.SharePermission.READ_AND_SHARE, false),
      Arguments.arguments(ACL.SharePermission.READ_ONLY.encode(), ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.SharePermission.READ_AND_WRITE.encode(), ACL.SharePermission.READ_ONLY, true),
      Arguments.arguments(ACL.SharePermission.READ_AND_WRITE.encode(), ACL.SharePermission.READ_AND_SHARE, false),
      Arguments.arguments(ACL.SharePermission.READ_AND_WRITE.encode(), ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.SharePermission.READ_AND_SHARE.encode(), ACL.SharePermission.READ_ONLY, true),
      Arguments.arguments(ACL.SharePermission.READ_AND_SHARE.encode(), ACL.SharePermission.READ_AND_WRITE, false),
      Arguments.arguments(ACL.SharePermission.READ_AND_SHARE.encode(), ACL.SharePermission.READ_WRITE_AND_SHARE, false),
      Arguments.arguments(ACL.SharePermission.READ_WRITE_AND_SHARE.encode(), ACL.SharePermission.READ_ONLY, true),
      Arguments.arguments(ACL.SharePermission.READ_WRITE_AND_SHARE.encode(), ACL.SharePermission.READ_AND_WRITE, true),
      Arguments.arguments(ACL.SharePermission.READ_WRITE_AND_SHARE.encode(), ACL.SharePermission.READ_AND_SHARE, true)
    );
  }

  static Stream<Arguments> permissionsToCheckTheSharePermissionConversion() {
    return Stream.of(
      Arguments.arguments(ACL.NONE, ACL.SharePermission.NONE),
      Arguments.arguments(ACL.READ, ACL.SharePermission.READ_ONLY),
      Arguments.arguments(
        ACL.SharePermission.READ_AND_WRITE.encode(),
        ACL.SharePermission.READ_AND_WRITE
      ),
      Arguments.arguments(
        ACL.SharePermission.READ_AND_SHARE.encode(),
        ACL.SharePermission.READ_AND_SHARE
      ),
      Arguments.arguments(
        ACL.SharePermission.READ_WRITE_AND_SHARE.encode(),
        ACL.SharePermission.READ_WRITE_AND_SHARE
      )
    );
  }

  @ParameterizedTest
  @MethodSource("aclsToCheckTheirRights")
  void givenAPermissionTheAclDecodeShouldReturnAnAclMappedWithTheCorrectRights(
    short permission,
    boolean canRead,
    boolean canWrite,
    boolean canShare,
    boolean canDelete,
    short encodedPermission
  ) {
    // Given: see parameters

    // When
    ACL acl = ACL.decode(permission);

    // Then
    Assertions.assertThat(acl.canRead()).isEqualTo(canRead);
    Assertions.assertThat(acl.canWrite()).isEqualTo(canWrite);
    Assertions.assertThat(acl.canShare()).isEqualTo(canShare);
    Assertions.assertThat(acl.canDelete()).isEqualTo(canDelete);
    Assertions.assertThat(acl.encode()).isEqualTo(encodedPermission);
  }

  @ParameterizedTest
  @MethodSource("pairOfAclsToCheckIfTheFirstIsContainedInTheOtherOne")
  void givenTwoAclPermissionsTheHasMethodShouldReturnIfTheFirstPermissionIsContainedInTheSecondOne(
    short firstPermission,
    SharePermission secondPermission,
    boolean expectedResult
  ){
    // Given
    ACL firstAcl = ACL.decode(firstPermission);

    // When
    boolean result = firstAcl.has(secondPermission);

    // Then
    Assertions.assertThat(result).isEqualTo(expectedResult);
  }

  @ParameterizedTest
  @MethodSource("permissionsToCheckTheSharePermissionConversion")
  void givenAPermissionTheGetSharePermissionShouldConvertToTheRelatedSharePermissionObject(
    short permission,
    SharePermission sharePermission
  ) {
    // Given
    ACL acl = ACL.decode(permission);

    // When
    SharePermission resultSharePermission = acl.getSharePermission();

    // Then
    Assertions.assertThat(resultSharePermission).isEqualTo(sharePermission);
  }

  @Test
  void givenTwoDifferentAclsTheLesserAclShouldReturnTheMoreRestrictiveOne() {
    // Given
    ACL readPermission = ACL.decode(ACL.READ);
    ACL ownerPermission = ACL.decode(ACL.OWNER);

    // When
    ACL restrictivePermission = ownerPermission.lesserACL(readPermission);
    ACL restrictivePermission2 = readPermission.lesserACL(ownerPermission);

    // Then
    Assertions.assertThat(restrictivePermission).isEqualTo(readPermission);
    Assertions
      .assertThat(restrictivePermission2)
      .isEqualTo(readPermission)
      .isEqualTo(restrictivePermission);
  }

  @Test
  void givenASharePermissionTheDecodeShouldReturnACorrespondingAcl() {
    // Given
    SharePermission sharePermission = SharePermission.READ_AND_SHARE;

    // When
    ACL acl = ACL.decode(sharePermission);

    // Then
    Assertions.assertThat(acl.encode()).isEqualTo(sharePermission.encode());
  }
}
