// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.quarkus.extensions.bootstrap.ApplicationConfigService;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Mutable test double for {@link FilesConfig}, the Quarkus port of the legacy {@code
 * MockFilesConfig}. Registered as an {@code @io.quarkus.test.Mock} alternative so it replaces the
 * production bean under {@code @QuarkusTest}; with no overrides set it delegates to the real
 * (boot-time Consul KV / config snapshot) values, so behaviour is identical unless a test opts in.
 *
 * <p>The acceptance seam ({@code QuarkusMocks#setMax*}, {@code setNotificationsEnabled}) resolves
 * this bean from Arc and flips the overrides. A {@code null} override means "fall through to the
 * real value" (matching the seam's documented contract). {@link #reset()} clears all overrides
 * between tests.
 */
@Mock
@ApplicationScoped
public class TestFilesConfig extends FilesConfig {

  private volatile Integer maxUploadableSizeMbOverride;
  private volatile Integer maxDownloadableSizeMbOverride;
  private volatile Integer maxNumberOfVersionsOverride;
  private volatile Boolean notificationsEnabledOverride;

  @Inject
  public TestFilesConfig(
      ApplicationConfigService applicationConfig,
      NetworkingConfigService networkingConfig,
      ServiceDiscoverHttpClient serviceDiscoverHttpClient) {
    super(applicationConfig, networkingConfig, serviceDiscoverHttpClient);
  }

  /**
   * No-args constructor required only so Arc can generate the client proxy: {@link FilesConfig} has
   * no no-arg constructor, so a synthetic one cannot be added automatically to this subclass. The
   * proxy forwards every call to the real contextual instance (built via the {@code @Inject}
   * constructor), so the {@code null} collaborators here are never dereferenced.
   */
  protected TestFilesConfig() {
    super(null, null, null);
  }

  public void setMaxUploadableSizeMb(Integer value) {
    this.maxUploadableSizeMbOverride = value;
  }

  public void setMaxDownloadableSizeMb(Integer value) {
    this.maxDownloadableSizeMbOverride = value;
  }

  public void setMaxNumberOfVersions(Integer value) {
    this.maxNumberOfVersionsOverride = value;
  }

  public void setAreNotificationsEnabled(boolean value) {
    this.notificationsEnabledOverride = value;
  }

  public void reset() {
    maxUploadableSizeMbOverride = null;
    maxDownloadableSizeMbOverride = null;
    maxNumberOfVersionsOverride = null;
    notificationsEnabledOverride = null;
  }

  @Override
  public int getMaxNumberOfVersions() {
    return maxNumberOfVersionsOverride != null
        ? maxNumberOfVersionsOverride
        : super.getMaxNumberOfVersions();
  }

  @Override
  public String getMaxNumberOfVersionsRaw() {
    return maxNumberOfVersionsOverride != null
        ? String.valueOf(maxNumberOfVersionsOverride)
        : super.getMaxNumberOfVersionsRaw();
  }

  @Override
  public Optional<Integer> getMaxUploadableFileSizeInMb() {
    return maxUploadableSizeMbOverride != null
        ? Optional.of(maxUploadableSizeMbOverride)
        : super.getMaxUploadableFileSizeInMb();
  }

  @Override
  public Optional<Integer> getMaxDownloadableFileSizeInMb() {
    return maxDownloadableSizeMbOverride != null
        ? Optional.of(maxDownloadableSizeMbOverride)
        : super.getMaxDownloadableFileSizeInMb();
  }

  @Override
  public boolean areNotificationsEnabled() {
    return notificationsEnabledOverride != null
        ? notificationsEnabledOverride
        : super.areNotificationsEnabled();
  }
}
