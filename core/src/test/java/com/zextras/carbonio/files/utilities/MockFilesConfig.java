// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.config.FilesConfig;

/**
 * Here one can override the standard behaviour of FilesConfigImpl to mock or otherwise differentiate
 * the standard configuration from the test configuration.
 */
public class MockFilesConfig extends FilesConfig {
    boolean areNotificationsEnabled = true;

    @Override
    public String getPageTokenSecretKey() {
      return "testSecretKey";
    }

    @Override
    public boolean areNotificationsEnabled() {
        return areNotificationsEnabled;
    }

    // Useful for testing
    public void setAreNotificationsEnabled(boolean areNotificationsEnabled) {
        this.areNotificationsEnabled = areNotificationsEnabled;
    }
}
