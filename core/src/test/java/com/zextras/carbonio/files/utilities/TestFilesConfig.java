// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.config.FilesConfigImpl;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.internal.client.StoragesClientImp;
import okhttp3.OkHttpClient;

public class TestFilesConfig extends FilesConfigImpl {

  @Override
  public Filestore getStorages() {
    return StoragesClientImp.atUrl("http://127.78.0.2:20002/", new OkHttpClient.Builder().build().newBuilder());
  }
}
