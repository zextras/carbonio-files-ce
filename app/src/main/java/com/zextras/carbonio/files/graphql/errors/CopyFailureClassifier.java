// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import graphql.GraphQLError;
import graphql.execution.ResultPath;
import java.util.function.Supplier;

/**
 * CE extension seam for turning a filestore-copy failure ({@code copyNodes} / {@code cloneVersion})
 * into a GraphQL error.
 *
 * <p>CE ships a behaviour-preserving {@link DefaultCopyFailureClassifier} default that always
 * returns the {@code defaultError} and never inspects the failure (CE cannot read a PowerStore
 * status). The Advanced edition supplies an {@code @Alternative @Priority(1)} implementation (the
 * same {@code @Alternative @Priority(1)} precedent used by the {@code QuotaChecker} seam) that maps
 * a storage over-quota failure (HTTP 422) into an {@code OVER_QUOTA_REACHED} error.
 */
public interface CopyFailureClassifier {

  /**
   * Classifies a copy {@code failure} into the {@link GraphQLError} to surface to the caller.
   *
   * @param failure the exception raised by the filestore copy
   * @param nodeId the id of the node whose copy failed
   * @param resultPath the GraphQL result path of the failing field
   * @param defaultError supplies the error CE would emit; implementations that cannot classify the
   *     failure must return it unchanged
   * @return the error to surface for the failed copy
   */
  GraphQLError classify(
      Throwable failure, String nodeId, ResultPath resultPath, Supplier<GraphQLError> defaultError);
}
