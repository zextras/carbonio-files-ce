// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.RestRequest;
import com.zextras.carbonio.quarkus.extensions.apiversioning.RestRequestConverter;

/**
 * {@code POST /upload} requires {@code DestinationId}; older clients send the optional {@code
 * ParentId}, which defaulted to the account root.
 */
@ApiConverter(operation = "com.zextras.carbonio.files.rest.resources.BlobResource#upload")
public class UploadDestinationIdConverter implements RestRequestConverter {

  @Override
  public void convert(RestRequest request) {
    String parentId = request.header(Headers.UPLOAD_PARENT_ID).orElse(RootId.LOCAL_ROOT);
    request.removeHeader(Headers.UPLOAD_PARENT_ID);
    request.setHeader(Headers.UPLOAD_DESTINATION_ID, parentId);
  }
}
