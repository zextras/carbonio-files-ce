// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.TestFilesConfig;
import io.quarkus.arc.Arc;
import java.util.Map;

/**
 * Builder for a {@link FilesTestApp} backed by the Quarkus stack. Replaces the retired
 * {@code GuiceNettyFilesTestAppBuilder}: the fluent {@code withXxx()} knobs no longer stand up a
 * per-test {@code Simulator} (the whole stack is a single shared {@code @QuarkusTest} application
 * plus {@link FilesStackTestResource}), so the dependency knobs are no-ops and only the ones that
 * carry data — {@code withUserManagement}, {@code withMaxNumberOfVersions} — actually do work
 * against the already-running application.
 *
 * <p>All acceptance-test bodies obtain their app through {@code
 * QuarkusFilesTestAppBuilder.aFilesTestApp()...build()} in a static {@code @BeforeAll} (or, for
 * {@code HealthApiIT}, per-test try-with-resources), exactly as before — only the impl class name
 * changed.
 */
public class QuarkusFilesTestAppBuilder {

  private QuarkusFilesTestAppBuilder() {}

  public static QuarkusFilesTestAppBuilder aFilesTestApp() {
    return new QuarkusFilesTestAppBuilder();
  }

  public QuarkusFilesTestAppBuilder withDatabase() {
    return this; // Postgres testcontainer is always up (FilesStackTestResource).
  }

  public QuarkusFilesTestAppBuilder withServiceDiscover() {
    return this; // Consul WireMock is always up.
  }

  public QuarkusFilesTestAppBuilder withMessageBroker() {
    return this; // No broker in the acceptance stack; message-broker effects use backdoor injectors.
  }

  public QuarkusFilesTestAppBuilder withStorages() {
    return this; // storages == the in-memory Filestore CDI bean.
  }

  public QuarkusFilesTestAppBuilder withPreview() {
    return this; // preview == the preview/mailbox WireMock.
  }

  public QuarkusFilesTestAppBuilder withDocsConnector() {
    return this;
  }

  public QuarkusFilesTestAppBuilder withMailbox() {
    return this; // mailbox == the preview/mailbox WireMock.
  }

  public QuarkusFilesTestAppBuilder withUserManagement(Map<String, String> users) {
    if (users != null) {
      users.forEach(
          (token, userId) ->
              FilesStackTestResource.getUserManagementService().registerToken(token, userId));
    }
    return this;
  }

  /**
   * Overrides {@code max-number-of-versions}. On Quarkus this is applied to the mutable {@link
   * TestFilesConfig} bean immediately (the app is already booted); every consumer (including
   * {@code NodeDataFetcher}) reads this cap live per-operation, so the override always takes
   * effect regardless of when the data-fetcher bean itself was constructed.
   */
  public QuarkusFilesTestAppBuilder withMaxNumberOfVersions(int maxVersions) {
    ((TestFilesConfig) Arc.container().instance(FilesConfig.class).get())
        .setMaxNumberOfVersions(maxVersions);
    return this;
  }

  public FilesTestApp build() {
    return new QuarkusFilesTestApp();
  }
}
