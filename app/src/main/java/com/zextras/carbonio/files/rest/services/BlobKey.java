// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;

/** The identity of one blob in the external store, within an owner. */
public record BlobKey(String nodeId, int version) {

  public static BlobKey of(FileVersion fileVersion) {
    return new BlobKey(fileVersion.getNodeId(), fileVersion.getVersion());
  }
}
