// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Type;

@Type("Permissions")
public class PermissionsModel {

  private final boolean canRead;
  private final boolean canWriteFile;
  private final boolean canWriteFolder;
  private final boolean canDelete;
  private final boolean canAddVersion;
  private final boolean canReadLink;
  private final boolean canChangeLink;
  private final boolean canShare;
  private final boolean canReadShare;
  private final boolean canChangeShare;

  public PermissionsModel(ACL acl) {
    this.canRead = acl.canRead();
    this.canWriteFile = acl.canWrite();
    this.canWriteFolder = acl.canWrite();
    this.canDelete = acl.canDelete();
    this.canAddVersion = acl.canWrite();
    this.canReadLink = acl.canRead();
    this.canChangeLink = acl.canWrite();
    this.canShare = acl.canShare();
    this.canReadShare = acl.canRead();
    this.canChangeShare = acl.canWrite();
  }

  @Name("can_read")
  public boolean isCanRead() {
    return canRead;
  }

  @Name("can_write_file")
  public boolean isCanWriteFile() {
    return canWriteFile;
  }

  @Name("can_write_folder")
  public boolean isCanWriteFolder() {
    return canWriteFolder;
  }

  @Name("can_delete")
  public boolean isCanDelete() {
    return canDelete;
  }

  @Name("can_add_version")
  public boolean isCanAddVersion() {
    return canAddVersion;
  }

  @Name("can_read_link")
  public boolean isCanReadLink() {
    return canReadLink;
  }

  @Name("can_change_link")
  public boolean isCanChangeLink() {
    return canChangeLink;
  }

  @Name("can_share")
  public boolean isCanShare() {
    return canShare;
  }

  @Name("can_read_share")
  public boolean isCanReadShare() {
    return canReadShare;
  }

  @Name("can_change_share")
  public boolean isCanChangeShare() {
    return canChangeShare;
  }
}
