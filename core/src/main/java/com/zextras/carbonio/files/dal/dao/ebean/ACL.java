// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files.GraphQL.Types;
import java.util.Objects;

/**
 * <p>This class represents an Access Control List (ACL). Each ACL instance represents the
 * permissions that a user can have on a specific object (e.g.: {@link Node}).</p>
 * <p>It associates <code>short</code> numbers with a specific name to represent clearly every
 * single permission. It also contains the {@link SharePermission} enumerator that is useful as
 * shortcut references to represent the union of multiple permissions (like {@link
 * SharePermission#READ_AND_WRITE}).</p>
 * <p><strong>Be aware</strong>: Selecting {@link #WRITE} permission doesn't automatically select
 * the {@link #READ} permission to true, if you want that, you must use the enumerator.</p>
 * <p>This class contains useful methods to check if the user can read, write, share and/or delete
 * the related object.</p>
 */
public class ACL {

  public static final short NONE  = 0;
  public static final short READ  = 1;
  public static final short WRITE = 2;
  public static final short SHARE = 4;
  public static final short OWNER = READ | WRITE | SHARE;

  private final boolean mCanRead;
  private final boolean mCanWrite;
  private final boolean mCanShare;
  private final boolean mCanDelete;

  /**
   * Creates an {@link ACL} representing the permissions of an object.
   *
   * @param permissions a <code>short</code> representing the permissions to decode.
   */
  ACL(short permissions) {
    mCanRead = (permissions & READ) == READ;
    mCanWrite = (permissions & WRITE) == WRITE;
    mCanDelete = (permissions & WRITE) == WRITE;
    mCanShare = (permissions & SHARE) == SHARE;
  }

  public static ACL decode(short permissions) {
    return new ACL(permissions);
  }

  public static ACL decode(SharePermission sharePermission) {
    return new ACL(sharePermission.encode());
  }

  public boolean canRead() {
    return mCanRead;
  }

  public boolean canWrite() {
    return mCanWrite;
  }

  public boolean canShare() {
    return mCanShare;
  }

  public boolean canDelete() {
    return mCanDelete;
  }

  /**
   * Encodes the ACL object into a <code>short</code>.
   *
   * @return a <code>short</code> representing an ACL.
   */
  public short encode() {
    short rights = 0;
    rights |= (mCanRead)
      ? READ
      : 0;
    rights |= (mCanWrite)
      ? WRITE
      : 0;
    rights |= (mCanShare)
      ? SHARE
      : 0;
    return rights;
  }

  /**
   * Checks which ACL is the more restrictive one.
   *
   * @param permissions is the {@link ACL} to be compared
   *
   * @return the {@link ACL} representing the more restrictive permissions.
   */
  public ACL lesserACL(ACL permissions) {
    return (encode() < permissions.encode())
      ? this
      : permissions;
  }

  /**
   * Checks if the current {@link ACL} has the specified {@link SharePermission}.
   *
   * @param permission is a {@link SharePermission} to check.
   *
   * @return true if the current {@link ACL} contains the specified permission, false otherwise.
   */
  public boolean has(SharePermission permission) {
    return (encode() & permission.encode()) == permission.encode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ACL acl = (ACL) o;
    return mCanRead == acl.mCanRead &&
      mCanWrite == acl.mCanWrite &&
      mCanShare == acl.mCanShare &&
      mCanDelete == acl.mCanDelete;
  }

  @Override
  public int hashCode() {
    return Objects.hash(mCanRead, mCanWrite, mCanShare, mCanDelete);
  }

  public SharePermission getSharePermission() {
    switch (encode()) {
      case 1:
        return SharePermission.READ_ONLY;
      case 3:
        return SharePermission.READ_AND_WRITE;
      case 5:
        return SharePermission.READ_AND_SHARE;
      case 7:
        return SharePermission.READ_WRITE_AND_SHARE;
      default:
        return SharePermission.NONE;
    }
  }

  /**
   * Represents shortcuts to combine multiple permissions together. This enumerator is also used to
   * the {@link Types#SHARE_PERMISSION}.
   */
  public enum SharePermission {
    NONE(ACL.NONE),
    READ_ONLY(READ),
    READ_AND_WRITE((short) (READ | WRITE)),
    READ_AND_SHARE((short) (READ | SHARE)),
    READ_WRITE_AND_SHARE((short) (READ | WRITE | SHARE));

    public final short mValue;

    SharePermission(short value) {
      mValue = value;
    }

    public short encode() {
      return mValue;
    }
  }
}
