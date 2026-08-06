// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.preview.sdk.PreviewClient;
import com.zextras.carbonio.preview.sdk.PreviewResponse;
import com.zextras.carbonio.preview.sdk.Query;
import com.zextras.carbonio.preview.sdk.QueryBuilder;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.text.MessageFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus/CDI port of the legacy Guice {@code PreviewService}. Logic preserved 1:1 (query building,
 * response mapping); the only adaptation is the transport (the carbonio-preview REST SDK {@link
 * PreviewClient}, produced by {@code PreviewClientProducer}) and CDI wiring. The unused {@code
 * FilesConfig} constructor parameter of the legacy class was dropped (dead code — it was never
 * read).
 */
@ApplicationScoped
public class PreviewService {

  private static final Logger logger = LoggerFactory.getLogger(PreviewService.class);

  private final NodeRepository nodeRepository;
  private final PreviewClient previewClient;

  @Inject
  public PreviewService(NodeRepository nodeRepository, PreviewClient previewClient) {
    this.nodeRepository = nodeRepository;
    this.previewClient = previewClient;
  }

  /**
   * Fetches the preview of an image if the requester has read rights.
   *
   * @return the fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or
   *     failed
   */
  public Try<BlobResponse> getPreviewOfImage(
      String ownerId,
      String nodeId,
      int version,
      String area,
      PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.of(area), queryParameters);

    logger.debug(MessageFormat.format("Image preview query built: {0}", query));

    return Try.of(() -> previewClient.getPreviewOfImage(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /** Fetches the thumbnail of an image if the requester has read rights. */
  public Try<BlobResponse> getThumbnailOfImage(
      String ownerId,
      String nodeId,
      int version,
      String area,
      PreviewQueryParameters queryParameters) {

    Query query = generateQuery(nodeId, version, ownerId, Optional.of(area), queryParameters);

    logger.debug(MessageFormat.format("Image thumbnail query built: {0}", query));

    return Try.of(() -> previewClient.getThumbnailOfImage(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /** Fetches the preview of a pdf if the requester has read rights. */
  public Try<BlobResponse> getPreviewOfPdf(
      String ownerId, String nodeId, int version, PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.empty(), queryParameters);
    logger.debug(MessageFormat.format("Pdf preview query built: {0}", query));

    return Try.of(() -> previewClient.getPreviewOfPdf(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /** Fetches the thumbnail of a pdf if the requester has read rights. */
  public Try<BlobResponse> getThumbnailOfPdf(
      String ownerId,
      String nodeId,
      int version,
      String area,
      PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.of(area), queryParameters);

    logger.debug(MessageFormat.format("Pdf thumbnail query built: {0}", query));

    return Try.of(() -> previewClient.getThumbnailOfPdf(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /** Fetches the preview of a document if the requester has read rights. */
  public Try<BlobResponse> getPreviewOfDocument(
      String ownerId, String nodeId, int version, PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.empty(), queryParameters);

    logger.info(MessageFormat.format("Document preview query built: {0}", query));

    return Try.of(() -> previewClient.getPreviewOfDocument(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /** Fetches the thumbnail of a document if the requester has read rights. */
  public Try<BlobResponse> getThumbnailOfDocument(
      String ownerId,
      String nodeId,
      int version,
      String area,
      PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.of(area), queryParameters);

    logger.debug(MessageFormat.format("Document thumbnail query built: {0}", query));

    return Try.of(() -> previewClient.getThumbnailOfDocument(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  private Query generateQuery(
      String nodeId,
      int version,
      String ownerId,
      Optional<String> optArea,
      PreviewQueryParameters queryParameters) {
    QueryBuilder parameterBuilder =
        new QueryBuilder().serviceType("files").fileId(nodeId).version(version).ownerId(ownerId);

    optArea.ifPresent(parameterBuilder::area);
    // The REST SDK forwards these values verbatim as query-string parameters, so they must
    // already be in the lower-case form the carbonio-preview server expects (the old gRPC SDK
    // lower-cased its enums internally; PreviewQueryParameters still exposes the upper-case
    // enum names, so we lower-case them here instead).
    queryParameters
        .getQuality()
        .ifPresent(quality -> parameterBuilder.quality(quality.toLowerCase()));
    queryParameters
        .getOutputFormat()
        .ifPresent(outputFormat -> parameterBuilder.outputFormat(outputFormat.toLowerCase()));
    parameterBuilder.crop(queryParameters.getCrop().orElse(false));
    queryParameters.getShape().ifPresent(shape -> parameterBuilder.shape(shape.toLowerCase()));
    queryParameters.getFirstPage().ifPresent(parameterBuilder::firstPage);
    queryParameters.getLastPage().ifPresent(parameterBuilder::lastPage);
    queryParameters.getLangTag().ifPresent(parameterBuilder::langTag);

    return parameterBuilder.build();
  }

  /**
   * Maps a {@link PreviewResponse} to a {@link BlobResponse} without buffering: the
   * carbonio-preview REST SDK response {@link java.io.InputStream} is handed through as-is and
   * streamed to the client by {@code TransferStreaming} over the raw Vert.x response (not
   * RESTEasy's entity writer). The {@link PreviewResponse} is intentionally NOT closed here: {@code
   * TransferStreaming} closes the stream after the pump, which is what releases the underlying HTTP
   * connection.
   */
  private BlobResponse mapResponseToBlobResponse(PreviewResponse response, String nodeId) {
    long length = response.getLength();
    return new BlobResponse(
        response.getContent(),
        nodeRepository.getNode(nodeId).get().getFullName(),
        length >= 0 ? length : null,
        response.getMimeType());
  }
}
