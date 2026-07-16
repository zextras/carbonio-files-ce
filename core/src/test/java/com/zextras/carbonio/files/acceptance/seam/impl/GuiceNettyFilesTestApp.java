// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.Mocks;
import com.zextras.carbonio.files.acceptance.seam.TestDataAccess;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;

/**
 * {@link FilesTestApp} implementation wrapping the current Guice+Netty {@link Simulator} stack.
 *
 * <p>This is the ONLY {@link FilesTestApp} implementation today. When the app is rewritten on
 * Quarkus, a sibling {@code QuarkusFilesTestApp} will be added in this package and the
 * acceptance-test bodies will not need to change.
 *
 * <p>Build instances via {@link GuiceNettyFilesTestAppBuilder}, not directly.
 */
public class GuiceNettyFilesTestApp implements FilesTestApp {

  private final Simulator simulator;
  private final TestDataAccess testDataAccess;
  private final Mocks mocks;

  GuiceNettyFilesTestApp(Simulator simulator) {
    this.simulator = simulator;
    this.testDataAccess = new GuiceNettyTestDataAccess(simulator);
    this.mocks = new GuiceNettyMocks(simulator);
  }

  @Override
  public HttpResponse send(HttpRequest request) {
    return TestUtils.sendRequest(request, simulator.getNettyChannel());
  }

  @Override
  public HttpResponse sendForm(HttpRequest request) {
    return TestUtils.sendFormRequest(request, simulator.getNettyChannel());
  }

  @Override
  public HttpResponse upload(HttpRequest request) {
    return TestUtils.sendUpload(request, simulator.getNettyChannel());
  }

  @Override
  public TestDataAccess backdoor() {
    return testDataAccess;
  }

  @Override
  public Mocks mocks() {
    return mocks;
  }

  @Override
  public void close() {
    simulator.stopAll();
  }
}
