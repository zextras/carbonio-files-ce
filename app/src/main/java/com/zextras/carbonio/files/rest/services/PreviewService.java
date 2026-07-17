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
import java.io.ByteArrayInputStream;
import java.text.MessageFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus/CDI port of the legacy Guice {@code PreviewService}. Logic preserved 1:1 (query
 * building, response mapping); the only adaptation is the transport (the carbonio-preview REST SDK
 * {@link PreviewClient}, produced by {@code PreviewClientProducer}) and CDI wiring. The unused
 * {@code FilesConfig} constructor parameter of the legacy class was dropped (dead code — it was
 * never read).
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
    queryParameters.getQuality().ifPresent(quality -> parameterBuilder.quality(quality.toLowerCase()));
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
   * Maps a {@link PreviewResponse} to a {@link BlobResponse}. Unlike {@code BlobService}'s node
   * downloads (which stream straight from storages — potentially huge files), the preview payload
   * is read eagerly into memory here: carbonio-preview responses (image/PDF/document previews and
   * thumbnails) are bounded in size, and the carbonio-preview REST SDK's underlying {@code
   * java.net.http.HttpClient} response {@link java.io.InputStream} does not play well with
   * RESTEasy Reactive's lazy entity streaming (observed as the request hanging until the client's
   * own socket read times out) — eagerly buffering sidesteps that instead of fighting it.
   */
  private BlobResponse mapResponseToBlobResponse(PreviewResponse response, String nodeId) {
    byte[] content;
    try (response) {
      content = response.getContent().readAllBytes();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("Failed to read preview response content", e);
    }
    return new BlobResponse(
        new ByteArrayInputStream(content),
        nodeRepository.getNode(nodeId).get().getFullName(),
        (long) content.length,
        response.getMimeType());
  }
}
