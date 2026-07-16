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
import org.mockserver.verify.VerificationTimes;

public class StoragesMockHelper {

  /**
   * A syntactically-valid {@code StoragesUploadResponse}/{@code StoragesUploadResponse}-shaped
   * JSON body (fields read by {@code com.zextras.storages.internal.pojo.StoragesUploadResponse}
   * via its Gson {@code @SerializedName}s: {@code digest}, {@code digest_algorithm}, {@code
   * size}). The exact digest/size values are arbitrary and stable — production only logs and
   * stores them (as {@code Node.size} / {@code FileVersion.digest}), it never compares them
   * against the actual uploaded bytes.
   */
  private static final String UPLOAD_SUCCESS_BODY =
      "{\"digest\":\"deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeefcafebabe\","
          + "\"digest_algorithm\":\"SHA-256\",\"size\":42}";

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
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(body.toString()));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint to return an HTTP 500 error,
   * simulating a complete PowerStore outage.
   */
  public void bulkDeleteError() {
    storagesMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(500));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint to return HTTP 200 with body {@code "{}"},
   * which the SDK deserialises as {@code ids=null} and throws a {@link NullPointerException}.
   * Production code treats this NPE as "all deletes succeeded".
   */
  public void bulkDeleteNullResponse() {
    storagesMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody("{}"));
  }

  /**
   * Mocks the PowerStore bulk-delete endpoint for version-level failures.
   * Each entry in {@code failedNodeVersions} is a pair of (nodeId, version).
   * An empty list means full success.
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
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(JsonBody.json(response)));
  }

  /**
   * Mocks the PowerStore upload endpoint (path {@code /upload}, per the {@code Filestore}
   * Retrofit interface's {@code @POST("upload")}/{@code @PUT("upload")} — the SDK's {@code
   * uploadPost}/{@code uploadPut} both hit the same relative path, distinguished only by HTTP
   * method; this stub matches BOTH since either can happen depending on whether the node is a
   * brand-new upload or an overwrite) to succeed with a syntactically-valid response.
   *
   * <p>ALSO stubs the plain {@code GET /download} endpoint to succeed generically (any node id/
   * version): {@code BlobService#verifyBlobExists} re-uses the download endpoint itself to check
   * that the just-uploaded blob is readable — there is no dedicated "exists" endpoint. Since an
   * upload's node id is server-generated (unknown ahead of time to the caller), this download stub
   * intentionally does not filter by node/version query parameters. Required by every upload
   * happy-path scenario.
   */
  public void uploadSucceeds() {
    storagesMock
        .when(HttpRequest.request().withPath("/upload"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(UPLOAD_SUCCESS_BODY));

    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/download"))
        .respond(HttpResponse.response().withStatusCode(200).withBody("upload-verify-ok"));
  }

  /**
   * Mocks the PowerStore upload endpoint to return an HTTP 500, simulating an upload failure
   * (production wraps this in a {@code DependencyException} -> 500, and rolls back the DB row
   * created for the new node/version).
   */
  public void uploadFails() {
    storagesMock
        .when(HttpRequest.request().withPath("/upload"))
        .respond(HttpResponse.response().withStatusCode(500));
  }

  /**
   * Mocks the upload itself succeeding but the post-upload existence check failing: {@code
   * BlobService#verifyBlobExists} re-uses {@code GET /download} (there is no dedicated "exists"
   * endpoint), so this stubs the upload endpoint to succeed and the download endpoint to report
   * the blob missing (404) — production treats that as an upload-verification failure
   * (-> {@code DependencyException} -> 500) and rolls back the node/version DB row.
   */
  public void verifyMissing() {
    storagesMock
        .when(HttpRequest.request().withPath("/upload"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(UPLOAD_SUCCESS_BODY));

    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/download"))
        .respond(HttpResponse.response().withStatusCode(404));
  }

  /**
   * Mocks the PowerStore copy endpoint (path {@code /copy}, {@code @PUT} per the {@code
   * Filestore} Retrofit interface — used by {@code copyFile}/{@code cloneVersion}) to succeed
   * with a syntactically-valid response.
   */
  public void copySucceeds() {
    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.PUT.toString()).withPath("/copy"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(UPLOAD_SUCCESS_BODY));
  }

  /**
   * Mocks the PowerStore copy endpoint to return an HTTP 500, simulating a filestore-copy failure
   * ({@code copyFile}/{@code cloneVersion}'s dependency-failure branch).
   */
  public void copyFails() {
    storagesMock
        .when(HttpRequest.request().withMethod(HttpMethod.PUT.toString()).withPath("/copy"))
        .respond(HttpResponse.response().withStatusCode(500));
  }

  /**
   * Verifies the PowerStore upload endpoint was hit exactly once for this {@code nodeId}/{@code
   * version} (either HTTP method — new-version POST or overwrite PUT both land on the same path).
   */
  public void verifyUploaded(String nodeId, int version) {
    storagesMock
        .verify(
            HttpRequest.request()
                .withPath("/upload")
                .withQueryStringParameter(Parameter.param("node", nodeId))
                .withQueryStringParameter(Parameter.param("version", String.valueOf(version)))
                .withQueryStringParameter(Parameter.param("type", "files")),
            VerificationTimes.atLeast(1));
  }
}
