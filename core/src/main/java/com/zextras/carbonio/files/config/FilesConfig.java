// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.filestore.api.Filestore;

import java.util.Properties;

public interface FilesConfig {
  Properties getProperties();
  UserManagementClient getUserManagementClient();
  PreviewClient getPreviewClient();
  Filestore getStoragesClient();
  int getMaxNumberOfFileVersion();
  String getDatabaseUrl();
  String getMailboxUrl();
  String getDocsConnectorUrl();
}
