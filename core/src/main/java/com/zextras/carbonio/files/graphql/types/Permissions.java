// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.types;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;

/**
 * <p>Represents a GraphQL Permissions object declared in the schema. This class must be used only
 * in a {@link graphql.schema.DataFetcher} when is necessary to return an object that {@link
 * graphql.GraphQL} library can decode and create the related JSON response.</p>
 * <p>For this reason every field has the same name and naming-style as the attributes declared in
 * the schema.</p>
 * <p>This class has neither getter nor setter because it is only a representation of a GraphQL
 * type and <strong>must not be used</strong> for other scopes.</p>
 */
public class Permissions {

  private final boolean can_read;
  private final boolean can_write_file;
  private final boolean can_write_folder;
  private final boolean can_delete;
  private final boolean can_add_version;
  private final boolean can_read_link;
  private final boolean can_change_link;
  private final boolean can_share;
  private final boolean can_read_share;
  private final boolean can_change_share;

  /**
   * Converts a {@link ACL} into a GraphQL Permissions object.
   *
   * @param permissions is an {@link ACL} containing all the permissions to convert.
   */
  private Permissions(ACL permissions) {
    can_read = permissions.canRead();
    can_write_file = permissions.canWrite();
    can_write_folder = permissions.canWrite();
    can_delete = permissions.canDelete();
    can_add_version = permissions.canWrite();
    can_read_link = permissions.canRead();
    can_change_link = permissions.canWrite();
    can_share = permissions.canShare();
    can_read_share = permissions.canRead();
    can_change_share = permissions.canWrite();
  }

  public static Permissions build(ACL permissions) {
    return new Permissions(permissions);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Permissions that = (Permissions) o;
    return can_read == that.can_read &&
      can_write_file == that.can_write_file &&
      can_write_folder == that.can_write_folder &&
      can_delete == that.can_delete &&
      can_add_version == that.can_add_version &&
      can_read_link == that.can_read_link &&
      can_change_link == that.can_change_link &&
      can_share == that.can_share &&
      can_read_share == that.can_read_share &&
      can_change_share == that.can_change_share;
  }
}
