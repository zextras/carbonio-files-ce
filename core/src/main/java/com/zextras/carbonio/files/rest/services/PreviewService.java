// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Config.Preview;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.preview.queries.Query;
import com.zextras.carbonio.preview.queries.Query.QueryBuilder;
import com.zextras.carbonio.preview.queries.enums.ServiceType;
import io.vavr.control.Try;
import java.text.MessageFormat;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreviewService {

  private static final Logger logger = LoggerFactory.getLogger(PreviewService.class);

  private final NodeRepository nodeRepository;
  private final String previewURL;

  @Inject
  public PreviewService(FilesConfig filesConfig, NodeRepository nodeRepository) {
    this.nodeRepository = nodeRepository;

    Properties properties = filesConfig.getProperties();
    previewURL =
        "http://"
            + properties.getProperty(Preview.URL, "127.78.0.2")
            + ":"
            + properties.getProperty(Preview.PORT, "20003");
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

    Try<com.zextras.carbonio.preview.queries.BlobResponse> response =
        PreviewClient.atURL(previewURL).getPreviewOfImage(query);

    return mapResponseToBlobResponse(response, nodeId);
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

    Try<com.zextras.carbonio.preview.queries.BlobResponse> response =
        PreviewClient.atURL(previewURL).getThumbnailOfImage(query);

    return mapResponseToBlobResponse(response, nodeId);
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

    Try<com.zextras.carbonio.preview.queries.BlobResponse> response =
        PreviewClient.atURL(previewURL).getPreviewOfPdf(query);

    return mapResponseToBlobResponse(response, nodeId);
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

    Try<com.zextras.carbonio.preview.queries.BlobResponse> response =
        PreviewClient.atURL(previewURL).getThumbnailOfPdf(query);

    return mapResponseToBlobResponse(response, nodeId);
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

    Try<com.zextras.carbonio.preview.queries.BlobResponse> response =
        PreviewClient.atURL(previewURL).getPreviewOfDocument(query);

    return mapResponseToBlobResponse(response, nodeId);
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

    Try<com.zextras.carbonio.preview.queries.BlobResponse> response =
        PreviewClient.atURL(previewURL).getThumbnailOfDocument(query);

    return mapResponseToBlobResponse(response, nodeId);
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
        new QueryBuilder()
            .setServiceType(ServiceType.FILES)
            .setFileId(nodeId)
            .setVersion(version)
            .setFileOwnerId(ownerId);

    optArea.ifPresent(parameterBuilder::setPreviewArea);
    queryParameters.getQuality().ifPresent(parameterBuilder::setQuality);
    queryParameters.getOutputFormat().ifPresent(parameterBuilder::setOutputFormat);
    queryParameters.getCrop().ifPresent(parameterBuilder::setCrop);
    queryParameters.getShape().ifPresent(parameterBuilder::setShape);
    queryParameters.getFirstPage().ifPresent(parameterBuilder::setFirstPage);
    queryParameters.getLastPage().ifPresent(parameterBuilder::setLastPage);
    queryParameters.getLocale().ifPresent(parameterBuilder::setLocale);

    return parameterBuilder.build();
  }

  /**
   * This method maps all fields of a {@link com.zextras.carbonio.preview.queries.BlobResponse}
   * object to the corresponding fields of a {@link BlobResponse} object.
   *
   * @param response is a {@link com.zextras.carbonio.preview.queries.BlobResponse} object to
   *     convert.
   * @param nodeId is a {@link String} representing the node id.
   * @return generated {@link Query}
   */
  private Try<BlobResponse> mapResponseToBlobResponse(
      Try<com.zextras.carbonio.preview.queries.BlobResponse> response, String nodeId) {
    return (response.isSuccess())
        ? Try.success(
            new BlobResponse(
                response.get().getContent(),
                nodeRepository.getNode(nodeId).get().getFullName(),
                response.get().getLength(),
                response.get().getMimeType()))
        : Try.failure(response.failed().get());
  }
}
