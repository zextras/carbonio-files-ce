// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.sdk;

import com.zextras.carbonio.files.sdk.rest.ApiClient;
import com.zextras.carbonio.files.sdk.rest.ApiException;
import com.zextras.carbonio.files.sdk.rest.api.InternalNodeResourceApi;
import com.zextras.carbonio.files.sdk.rest.model.CreateFolderRequest;
import com.zextras.carbonio.files.sdk.rest.model.CreatePublicLinkRequest;
import com.zextras.carbonio.files.sdk.rest.model.DeleteAllRequest;
import com.zextras.carbonio.files.sdk.rest.model.DeleteAllResponse;
import com.zextras.carbonio.files.sdk.rest.model.InternalNodeDto;
import com.zextras.carbonio.files.sdk.rest.model.InternalNodeIdDto;
import com.zextras.carbonio.files.sdk.rest.model.PublicLinkDto;
import com.zextras.carbonio.files.sdk.streaming.RestStreamingSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trusted-caller client for carbonio-files' {@code /internal} REST surface: every call carries the
 * acting {@code userId} EXPLICITLY (no cookie), the same trusted-caller contract as the retired
 * gRPC SDK and the retired cookie-based {@code carbonio-files-sdk} {@code FilesClient}. Method
 * names/semantics mirror {@code FilesClient} (and its {@code InternalFilesClient} companion) for
 * easy consumer migration:
 *
 * <ul>
 *   <li>{@link #getNode}, {@link #createFolder}, {@link #createPublicLink}, {@link
 *       #deleteAllNodesAndBlobs} go through the generated JSON client ({@link
 *       InternalNodeResourceApi}, built from the committed {@code app/docs/openapi.yaml}),
 *       unwrapping {@code ApiResponse<T>}/{@code ApiException} into a plain DTO or a {@link
 *       FilesInternalClientException}.
 *   <li>{@link #uploadFile}, {@link #uploadFileVersion}, {@link #downloadFile} go through {@link
 *       RestStreamingSupport} instead: the generated client's blob methods buffer the whole body
 *       into a temp {@link java.io.File} (see {@code InternalBlobResourceApi}), which defeats the
 *       point of streaming a potentially large blob. {@link RestStreamingSupport} streams straight
 *       from/to the caller-supplied {@link InputStream}, never buffering the whole file.
 * </ul>
 *
 * <p>Blob metadata (filename, parent id, node id, overwrite-version) is carried in HTTP headers,
 * exactly as {@code InternalBlobResource} on the server side expects. {@code Content-Length} is
 * deliberately NOT one of them: {@link java.net.http.HttpClient} treats it as a restricted header
 * that callers may not set directly (it throws {@link IllegalArgumentException} if attempted), and
 * {@link java.net.http.HttpRequest.BodyPublishers#ofInputStream} always streams via chunked
 * transfer-encoding regardless, so an explicit length could never be honored anyway. The server
 * tolerates a missing {@code Content-Length} (treats it as unknown, {@code -1}), so this is a
 * documented no-op rather than a workaround; the {@code length} parameter each upload method
 * accepts exists purely for call-site symmetry with the retired SDKs.
 */
public final class FilesInternalClient {

  private static final String HEADER_FILENAME = "Filename";
  private static final String HEADER_PARENT_ID = "ParentId";
  private static final String HEADER_NODE_ID = "NodeId";
  private static final String HEADER_OVERWRITE_VERSION = "OverwriteVersion";

  private static final String INTERNAL_ACCOUNTS_PATH = "/internal/accounts/";

  /** Matches the trailing "... failed with status NNN: ..." shape of the IOExceptions thrown by
   *  {@link RestStreamingSupport}, so a blob-op failure can still expose an HTTP status code. */
  private static final Pattern STATUS_CODE_PATTERN = Pattern.compile("status (\\d{3}):");

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final InternalNodeResourceApi nodeApi;
  private final HttpClient blobHttpClient;

  private FilesInternalClient(String url) {
    ApiClient apiClient = new ApiClient();
    apiClient.updateBaseUri(url);
    this.baseUrl = apiClient.getBaseUri();
    this.nodeApi = new InternalNodeResourceApi(apiClient);
    this.blobHttpClient = RestStreamingSupport.http1Client();
  }

  /**
   * Creates a new instance of the {@link FilesInternalClient}.
   *
   * @param url the base URL of the Files service, e.g. {@code http://127.0.0.1:10000}.
   * @return an instance of the {@link FilesInternalClient}.
   */
  public static FilesInternalClient atURL(String url) {
    return new FilesInternalClient(url);
  }

  // -------------------------------------------------------------------------------------- getNode

  /**
   * Fetches a node's metadata, as {@code userId} sees it (permissions included).
   *
   * @throws FilesInternalClientException wrapping a 404 (node does not exist) or 403 (exists, but
   *     {@code userId} cannot read it) response, or any transport-level failure.
   */
  public InternalNodeDto getNode(String userId, String nodeId) {
    try {
      return nodeApi.internalAccountsUserIdNodesNodeIdGet(nodeId, userId);
    } catch (ApiException e) {
      throw mapApiException("getNode", e);
    }
  }

  // --------------------------------------------------------------------------------- createFolder

  /**
   * Creates a folder named {@code name} under {@code destinationId}, on behalf of {@code userId}.
   *
   * @return the id of the newly-created folder.
   */
  public String createFolder(String userId, String destinationId, String name) {
    CreateFolderRequest request =
        new CreateFolderRequest().userId(userId).destinationId(destinationId).name(name);
    try {
      InternalNodeIdDto response = nodeApi.internalFoldersPost(request);
      return response.getNodeId();
    } catch (ApiException e) {
      throw mapApiException("createFolder", e);
    }
  }

  // ----------------------------------------------------------------------------- createPublicLink

  /**
   * Creates a public link for {@code nodeId}, on behalf of {@code userId}.
   *
   * @return the generated public URL.
   */
  public String createPublicLink(String userId, String nodeId) {
    CreatePublicLinkRequest request = new CreatePublicLinkRequest().userId(userId).nodeId(nodeId);
    try {
      PublicLinkDto response = nodeApi.internalLinksPost(request);
      return response.getUrl();
    } catch (ApiException e) {
      throw mapApiException("createPublicLink", e);
    }
  }

  // ----------------------------------------------------------------------- deleteAllNodesAndBlobs

  /**
   * Deletes every node (and its blobs) owned by {@code userId}.
   *
   * @return {@code true} if the deletion completed.
   */
  public boolean deleteAllNodesAndBlobs(String userId) {
    DeleteAllRequest request = new DeleteAllRequest().userId(userId);
    try {
      DeleteAllResponse response = nodeApi.internalNodesDelete(request);
      return Boolean.TRUE.equals(response.getDeleted());
    } catch (ApiException e) {
      throw mapApiException("deleteAllNodesAndBlobs", e);
    }
  }

  // ------------------------------------------------------------------------------------ uploadFile

  /**
   * Uploads a new file under {@code parentId}, on behalf of {@code userId}. {@code
   * fileStreamSupplier} is invoked exactly once (no retry is attempted by this client) and must
   * return a fresh, unread {@link InputStream}; the body is streamed straight from it, never
   * buffered whole in memory. {@code length} is accepted for parity with the retired SDKs but is
   * NOT sent as a header (see the class javadoc).
   *
   * @return the id of the newly-created file node.
   */
  public String uploadFile(
      String userId,
      String parentId,
      String filename,
      String mimeType,
      Supplier<InputStream> fileStreamSupplier,
      long length) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(HEADER_FILENAME, encodeFilename(filename));
    headers.put(HEADER_PARENT_ID, parentId);

    URI uri = URI.create(baseUrl + INTERNAL_ACCOUNTS_PATH + encodePathSegment(userId) + "/upload");
    HttpResponse<String> response =
        sendRawUpload("uploadFile", uri, headers, mimeType, fileStreamSupplier);
    return readNodeId(response.body());
  }

  // ----------------------------------------------------------------------------- uploadFileVersion

  /**
   * Uploads a new version of {@code nodeId}, on behalf of {@code userId}. Same streaming/{@code
   * length} contract as {@link #uploadFile}.
   *
   * @return the newly-created version number.
   */
  public int uploadFileVersion(
      String userId,
      String nodeId,
      String filename,
      String mimeType,
      Supplier<InputStream> fileStreamSupplier,
      long length,
      boolean overwrite) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(HEADER_FILENAME, encodeFilename(filename));
    headers.put(HEADER_NODE_ID, nodeId);
    headers.put(HEADER_OVERWRITE_VERSION, String.valueOf(overwrite));

    URI uri =
        URI.create(baseUrl + INTERNAL_ACCOUNTS_PATH + encodePathSegment(userId) + "/upload-version");
    HttpResponse<String> response =
        sendRawUpload("uploadFileVersion", uri, headers, mimeType, fileStreamSupplier);
    return readVersion(response.body());
  }

  // -------------------------------------------------------------------------------- downloadFile

  /**
   * Downloads the blob of {@code nodeId} (its current version, or {@code version} if present), on
   * behalf of {@code userId}, as a live, streamed {@link InputStream} never buffered in memory.
   * The caller is responsible for closing the returned stream.
   */
  public InputStream downloadFile(String userId, String nodeId, Optional<Integer> version) {
    StringBuilder path =
        new StringBuilder(baseUrl)
            .append(INTERNAL_ACCOUNTS_PATH)
            .append(encodePathSegment(userId))
            .append("/download/")
            .append(encodePathSegment(nodeId));
    version.ifPresent(v -> path.append('/').append(v));

    URI uri = URI.create(path.toString());
    try {
      return RestStreamingSupport.downloadStream(blobHttpClient, uri, Map.of(), null);
    } catch (IOException e) {
      throw mapStreamingException("downloadFile", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FilesInternalClientException("downloadFile was interrupted", -1, e);
    }
  }

  // --------------------------------------------------------------------------------------- shared

  private HttpResponse<String> sendRawUpload(
      String operation,
      URI uri,
      Map<String, String> headers,
      String contentType,
      Supplier<InputStream> bodyStreamSupplier) {
    try {
      return RestStreamingSupport.uploadStreamRaw(
          blobHttpClient, uri, headers, contentType, bodyStreamSupplier, null);
    } catch (IOException e) {
      throw mapStreamingException(operation, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FilesInternalClientException(operation + " was interrupted", -1, e);
    }
  }

  /**
   * Base64-encodes the filename (UTF-8), exactly the encoding {@code InternalBlobResource} decodes
   * on the server side.
   */
  private static String encodeFilename(String filename) {
    return Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
  }

  private static String encodePathSegment(String segment) {
    return ApiClient.urlEncode(segment);
  }

  /**
   * Parses the {@code {"nodeId": "...", "version": N}} JSON body {@code InternalBlobResource}
   * returns (see {@code UploadVersionResponse}) and extracts {@code nodeId}.
   */
  private static String readNodeId(String responseBody) {
    return parseUploadResponse(responseBody).path("nodeId").asText(null);
  }

  /** Same as {@link #readNodeId}, but for the {@code version} field. */
  private static int readVersion(String responseBody) {
    return parseUploadResponse(responseBody).path("version").asInt();
  }

  private static JsonNode parseUploadResponse(String responseBody) {
    try {
      return JSON_MAPPER.readTree(responseBody);
    } catch (IOException e) {
      throw new FilesInternalClientException(
          "Failed to parse the upload response body: " + e.getMessage(), -1, e);
    }
  }

  private static FilesInternalClientException mapApiException(String operation, ApiException e) {
    return new FilesInternalClientException(
        operation + " failed with status " + e.getCode() + ": " + e.getMessage(),
        e.getCode(),
        e);
  }

  /**
   * Wraps an {@link IOException} thrown by {@link RestStreamingSupport}, recovering the HTTP
   * status code from its message (see {@link #STATUS_CODE_PATTERN}) when the failure was a
   * non-2xx response rather than a pure transport error.
   */
  private static FilesInternalClientException mapStreamingException(
      String operation, IOException e) {
    int statusCode = extractStatusCode(e.getMessage());
    return new FilesInternalClientException(
        operation + " failed: " + e.getMessage(), statusCode, e);
  }

  private static int extractStatusCode(String message) {
    if (message == null) {
      return -1;
    }
    Matcher matcher = STATUS_CODE_PATTERN.matcher(message);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
  }
}
