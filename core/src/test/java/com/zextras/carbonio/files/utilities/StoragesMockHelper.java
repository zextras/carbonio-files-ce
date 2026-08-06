// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.storages.internal.pojo.Query;
import com.zextras.storages.internal.pojo.StoragesBulkDeleteResponse;
import io.netty.handler.codec.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
   * Mocks the PowerStore bulk-delete endpoint. The {@code failedIds} parameter lists node IDs whose
   * blob deletion should be reported as failed; an empty list means full success.
   */
  public void bulkDelete(List<String> failedIds) {
    // Build the JSON body explicitly so an EMPTY failed list serialises to the real
    // full-success signal {"ids":[]} (a non-null empty array), which the SDK maps to an
    // empty List. JsonBody.json() of an object whose only field is an empty collection
    // can drop the field entirely (-> {}), which the SDK then reads as ids=null and turns
    // into an NPE — i.e. it would NOT exercise the genuine empty-list success path.
    StringBuilder body = new StringBuilder("{\"ids\":[");
    for (int i = 0; i < failedIds.size(); i++) {
      if (i > 0) body.append(',');
      body.append("{\"node\":\"").append(failedIds.get(i)).append("\",\"type\":\"files\"}");
    }
    body.append("]}");
    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.POST.toString()).withPath("/bulk-delete"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(body.toString()));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint to return an HTTP 500 error, simulating a complete
   * PowerStore outage.
   */
  public void bulkDeleteError() {
    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.POST.toString()).withPath("/bulk-delete"))
        .respond(HttpResponse.response().withStatusCode(500));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint to return HTTP 200 with body {@code "{}"}, which the
   * SDK deserialises as {@code ids=null} and throws a {@link NullPointerException}. Production code
   * treats this NPE as "all deletes succeeded".
   */
  public void bulkDeleteNullResponse() {
    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.POST.toString()).withPath("/bulk-delete"))
        .respond(HttpResponse.response().withStatusCode(200).withBody("{}"));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint for version-level failures. Each entry in {@code
   * failedNodeVersions} is a pair of (nodeId, version). An empty list means full success.
   */
  public void bulkDeleteWithVersions(List<Map.Entry<String, Integer>> failedNodeVersions) {
    final StoragesBulkDeleteResponse response = new StoragesBulkDeleteResponse();
    List<Query> queries = new java.util.ArrayList<>();
    for (Map.Entry<String, Integer> entry : failedNodeVersions) {
      Query query = new Query();
      query.setNode(entry.getKey());
      query.setVersion(entry.getValue().longValue());
      query.setType("files");
      queries.add(query);
    }
    response.setIds(queries);
    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.POST.toString()).withPath("/bulk-delete"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(JsonBody.json(response)));
  }
}
