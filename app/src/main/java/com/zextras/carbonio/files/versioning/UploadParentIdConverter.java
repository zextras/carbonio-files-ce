// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.RestRequest;
import com.zextras.carbonio.quarkus.extensions.apiversioning.RestRequestConverter;

@ApiConverter(operation = "com.zextras.carbonio.files.rest.resources.BlobResource#upload")
public class UploadParentIdConverter implements RestRequestConverter {

  @Override
  public void convert(RestRequest request) {
    if (request.header(Headers.UPLOAD_PARENT_ID).isEmpty()) {
      request.setHeader(Headers.UPLOAD_PARENT_ID, RootId.LOCAL_ROOT);
    }
  }
}
