// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;

import java.util.Map;

/**
 * Builder for a {@link FilesTestApp} backed by the current Guice+Netty stack.
 *
 * <p>Mirrors {@link SimulatorBuilder}'s fluent knobs, added one at a time as acceptance tests
 * need them (see the Phase-1 migration plan) — do not try to cover every {@code SimulatorBuilder}
 * knob upfront. Extend with more {@code withXxx()} methods as later migrations require
 * additional {@code Simulator} capabilities (message broker, preview, docs-connector, etc.).
 * Never expose {@link Simulator} or {@code com.google.inject.Injector} outside this package.
 */
public class GuiceNettyFilesTestAppBuilder {

  private final SimulatorBuilder simulatorBuilder = SimulatorBuilder.aSimulator().init();

  private GuiceNettyFilesTestAppBuilder() {}

  public static GuiceNettyFilesTestAppBuilder aFilesTestApp() {
    return new GuiceNettyFilesTestAppBuilder();
  }

  public GuiceNettyFilesTestAppBuilder withDatabase() {
    simulatorBuilder.withDatabase();
    return this;
  }

  public GuiceNettyFilesTestAppBuilder withServiceDiscover() {
    simulatorBuilder.withServiceDiscover();
    return this;
  }

  public GuiceNettyFilesTestAppBuilder withMessageBroker() {
    simulatorBuilder.withMessageBroker();
    return this;
  }

  public GuiceNettyFilesTestAppBuilder withStorages() {
    simulatorBuilder.withStorages();
    return this;
  }

  public GuiceNettyFilesTestAppBuilder withPreview() {
    simulatorBuilder.withPreview();
    return this;
  }

  public GuiceNettyFilesTestAppBuilder withDocsConnector() {
    simulatorBuilder.withDocsConnector();
    return this;
  }

  public GuiceNettyFilesTestAppBuilder withUserManagement(Map<String, String> users) {
    simulatorBuilder.withUserManagement(users);
    return this;
  }

  public FilesTestApp build() {
    Simulator simulator = simulatorBuilder.build().start();
    // Transport selection WITHOUT touching any test body: opt in to the real-HTTP transport
    // (Option B) with -Dfiles.test.transport=http; the DEFAULT stays the embedded EmbeddedChannel
    // transport, so plain `mvn verify` behavior is unchanged. Both drive the SAME acceptance-test
    // bodies through the same neutral FilesTestApp seam.
    if ("http".equalsIgnoreCase(System.getProperty("files.test.transport"))) {
      return new RealHttpFilesTestApp(simulator);
    }
    return new GuiceNettyFilesTestApp(simulator);
  }
}
