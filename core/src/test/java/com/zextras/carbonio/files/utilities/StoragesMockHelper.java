// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.storages.internal.pojo.Query;
import com.zextras.storages.internal.pojo.StoragesBulkDeleteResponse;
import io.netty.handler.codec.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.Parameter;

public class StoragesMockHelper {

  private final MockServerClient storagesMock;

  public StoragesMockHelper(MockServerClient storagesMock) {
    this.storagesMock = storagesMock;
  }

  public void getBlob(String nodeId, int version) {
    storagesMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download")
                .withQueryStringParameter(Parameter.param("node", nodeId))
                .withQueryStringParameter(Parameter.param("version", String.valueOf(version)))
                .withQueryStringParameter(Parameter.param("type", "files")))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody((nodeId + version).getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint. The {@code failedIds} parameter lists
   * node IDs whose blob deletion should be reported as failed; an empty list means full success.
   */
  public void bulkDelete(List<String> failedIds) {
    final StoragesBulkDeleteResponse response = new StoragesBulkDeleteResponse();
    List<Query> queries = new java.util.ArrayList<>();
    for (String id : failedIds) {
      Query query = new Query();
      query.setNode(id);
      query.setType("files");
      queries.add(query);
    }
    response.setIds(queries);
    storagesMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(JsonBody.json(response)));
  }
}
