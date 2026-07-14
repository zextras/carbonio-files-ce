// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.acceptance.seam.Mocks;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
import com.zextras.carbonio.files.utilities.StoragesMockHelper;
import io.netty.handler.codec.http.HttpMethod;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.Parameter;
import org.mockserver.verify.VerificationTimes;

import java.util.List;

/**
 * {@link Mocks} implementation delegating to {@link StoragesMockHelper} and the {@link
 * Simulator}'s MockServer clients. {@code org.mockserver.*} types stay confined to this class and
 * to {@link StoragesMockHelper} — never in a test body.
 */
class GuiceNettyMocks implements Mocks {

  private final Simulator simulator;
  private final StoragesMockHelper storagesMockHelper;

  GuiceNettyMocks(Simulator simulator) {
    this.simulator = simulator;
    this.storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @Override
  public void storagesBulkDeleteSucceeds(List<String> failedIds) {
    storagesMockHelper.bulkDelete(failedIds);
  }

  @Override
  public void storagesBulkDeleteFails() {
    storagesMockHelper.bulkDeleteError();
  }

  @Override
  public void storagesBulkDeleteReturnsNullResponse() {
    storagesMockHelper.bulkDeleteNullResponse();
  }

  @Override
  public void setNotificationsEnabled(boolean enabled) {
    ((MockFilesConfig) simulator.getInjector().getInstance(FilesConfig.class))
        .setAreNotificationsEnabled(enabled);
  }

  @Override
  public void userManagementDown() {
    simulator.shutdownUserManagementServer();
  }

  @Override
  public void storagesLive() {
    simulator
        .getStoragesMock()
        .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
        .respond(HttpResponse.response().withStatusCode(200));
  }

  @Override
  public void storagesUnreachable() {
    simulator
        .getStoragesMock()
        .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
        .respond(HttpResponse.response().withStatusCode(502));
  }

  @Override
  public void previewReady() {
    simulator
        .getPreviewMock()
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/health/ready/"))
        .respond(HttpResponse.response().withStatusCode(200));
  }

  @Override
  public void docsConnectorLive() {
    simulator
        .getDocsConnectorMock()
        .when(
            HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/q/health/live"))
        .respond(HttpResponse.response().withStatusCode(200));
  }

  @Override
  public void storagesServesBlob(String nodeId, int version) {
    storagesMockHelper.getBlob(nodeId, version);
  }

  @Override
  public void storagesDownloadConnectionDrops() {
    simulator
        .getStoragesMock()
        .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/download"))
        .error(HttpError.error().withDropConnection(true));
  }

  @Override
  public void verifyStoragesDownloaded(String nodeId, int version) {
    simulator
        .getStoragesMock()
        .verify(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download")
                .withQueryStringParameter(Parameter.param("node", nodeId))
                .withQueryStringParameter(Parameter.param("version", String.valueOf(version)))
                .withQueryStringParameter(Parameter.param("type", "files")),
            VerificationTimes.once());
  }

  @Override
  public void verifyStoragesNeverDownloaded() {
    simulator
        .getStoragesMock()
        .verify(
            HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/download"),
            VerificationTimes.never());
  }

  @Override
  public String previewServes(String pathEndpoint, String fileOwnerId, byte[] content, String mediaType) {
    HttpRequest request =
        HttpRequest.request()
            .withMethod(HttpMethod.GET.toString())
            .withPath(pathEndpoint)
            .withQueryStringParameter(new Parameter("service_type", "files"))
            .withHeader("FileOwnerId", fileOwnerId);

    if (pathEndpoint.contains("document")) {
      request.withQueryStringParameter(new Parameter("lang_tag", "en"));
    }

    return simulator
        .getPreviewMock()
        .when(request)
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(new BinaryBody(content))
                .withContentType(MediaType.parse(mediaType)))[0]
        .getId();
  }

  @Override
  public void verifyPreviewServed(String expectationId) {
    simulator.getPreviewMock().verify(expectationId).clear(expectationId);
  }

  @Override
  public void reset() {
    simulator.reinitializeMocks();
  }
}
