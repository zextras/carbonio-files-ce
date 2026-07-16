// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.config.FilesConfig;
import java.util.Optional;

/**
 * Here one can override the standard behaviour of FilesConfigImpl to mock or otherwise differentiate
 * the standard configuration from the test configuration.
 */
public class MockFilesConfig extends FilesConfig {
    boolean areNotificationsEnabled = true;

    // null = fall through to the real Service-Discover-backed FilesConfig behaviour.
    private Integer maxUploadableFileSizeInMb;
    private Integer maxDownloadableFileSizeInMb;
    private Integer maxNumberOfFileVersion;

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

    @Override
    public Optional<Integer> getMaxUploadableFileSizeInMb() {
        return maxUploadableFileSizeInMb != null
            ? Optional.of(maxUploadableFileSizeInMb)
            : super.getMaxUploadableFileSizeInMb();
    }

    // Useful for testing. Pass null to fall back to the real Service-Discover-backed value.
    public void setMaxUploadableFileSizeInMb(Integer maxUploadableFileSizeInMb) {
        this.maxUploadableFileSizeInMb = maxUploadableFileSizeInMb;
    }

    @Override
    public Optional<Integer> getMaxDownloadableFileSizeInMb() {
        return maxDownloadableFileSizeInMb != null
            ? Optional.of(maxDownloadableFileSizeInMb)
            : super.getMaxDownloadableFileSizeInMb();
    }

    // Useful for testing. Pass null to fall back to the real Service-Discover-backed value.
    public void setMaxDownloadableFileSizeInMb(Integer maxDownloadableFileSizeInMb) {
        this.maxDownloadableFileSizeInMb = maxDownloadableFileSizeInMb;
    }

    @Override
    public int getMaxNumberOfFileVersion() {
        return maxNumberOfFileVersion != null ? maxNumberOfFileVersion : super.getMaxNumberOfFileVersion();
    }

    // Useful for testing. Pass null to fall back to the real Service-Discover-backed value.
    public void setMaxNumberOfFileVersion(Integer maxNumberOfFileVersion) {
        this.maxNumberOfFileVersion = maxNumberOfFileVersion;
    }
}
