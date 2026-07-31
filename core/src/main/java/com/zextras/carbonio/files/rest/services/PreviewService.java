// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.preview.sdk.PreviewClient;
import com.zextras.carbonio.preview.sdk.PreviewResponse;
import com.zextras.carbonio.preview.sdk.Query;
import com.zextras.carbonio.preview.sdk.QueryBuilder;
import io.vavr.control.Try;
import java.text.MessageFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreviewService {

  private static final Logger logger = LoggerFactory.getLogger(PreviewService.class);

  private final NodeRepository nodeRepository;
  private final PreviewClient previewClient;

  @Inject
  public PreviewService(
      FilesConfig filesConfig, NodeRepository nodeRepository, PreviewClient previewClient) {
    this.nodeRepository = nodeRepository;
    this.previewClient = previewClient;
  }

  /**
   * This method fetch the preview of an image if the requester has read rights.
   *
   * @param ownerId is a {@link String} representing the id of the file owner.
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code> representing the version of the file to fetch.
   * @param area is a {@link String} containing the area (widthxheight) of the preview.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or failed
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

  /**
   * This method fetch the thumbnail of an image if the requester has read rights.
   *
   * @param ownerId is a {@link String} representing the id of the file owner.
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code> representing the version of the file to fetch.
   * @param area is a {@link String} containing the area (widthxheight) of the preview.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or failed
   */
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

  /**
   * This method fetch the preview of a pdf if the requester has read rights.
   *
   * @param ownerId is a {@link String} representing the id of the file owner.
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code> representing the version of the file to fetch.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or failed
   */
  public Try<BlobResponse> getPreviewOfPdf(
      String ownerId, String nodeId, int version, PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.empty(), queryParameters);
    logger.debug(MessageFormat.format("Pdf preview query built: {0}", query));

    return Try.of(() -> previewClient.getPreviewOfPdf(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /**
   * This method fetch the thumbnail of a pdf if the requester has read rights.
   *
   * @param ownerId is a {@link String} representing the id of the file owner.
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code> representing the version of the file to fetch.
   * @param area is a {@link String} containing the area (widthxheight) of the preview.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or failed
   */
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

  /**
   * This method fetch the preview of a document if the requester has read rights.
   *
   * @param ownerId is a {@link String} representing the id of the file owner.
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code> representing the version of the file to fetch.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or failed
   */
  public Try<BlobResponse> getPreviewOfDocument(
      String ownerId, String nodeId, int version, PreviewQueryParameters queryParameters) {
    Query query = generateQuery(nodeId, version, ownerId, Optional.empty(), queryParameters);

    logger.info(MessageFormat.format("Document preview query built: {0}", query));

    return Try.of(() -> previewClient.getPreviewOfDocument(query))
        .map(response -> mapResponseToBlobResponse(response, nodeId));
  }

  /**
   * This method fetch the thumbnail of a document if the requester has read rights.
   *
   * @param ownerId is a {@link String} representing the id of the requester.
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code> representing the version of the file to fetch.
   * @param area is a {@link String} containing the area (widthxheight) of the preview.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return fetched {@link BlobResponse} wrapped in a {@link Try} that can be successful or failed
   */
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

  /**
   * This method generates a valid query object.
   *
   * @param nodeId is a {@link String} representing the id of the file to fetch.
   * @param version is a <code> integer </code>the integer representing the version of the file to
   *     fetch.
   * @param optArea is a {@link Optional} containing the area (widthxheight) of the preview.
   * @param queryParameters is a {@link PreviewQueryParameters} containing all the query parameters.
   * @return generated {@link Query}
   */
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
   * This method maps all fields of a {@link PreviewResponse} object to the corresponding fields of
   * a {@link BlobResponse} object.
   *
   * @param response is a {@link PreviewResponse} object to convert.
   * @param nodeId is a {@link String} representing the node id.
   * @return the mapped {@link BlobResponse}
   */
  private BlobResponse mapResponseToBlobResponse(PreviewResponse response, String nodeId) {
    return new BlobResponse(
        response.getContent(),
        nodeRepository.getNode(nodeId).get().getFullName(),
        response.getLength(),
        response.getMimeType());
  }
}
