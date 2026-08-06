// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import graphql.GraphQLError;
import graphql.execution.ResultPath;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

/**
 * CE default {@link CopyFailureClassifier}: it ignores the failure and always returns the error CE
 * already produces, preserving the current behaviour exactly. The Advanced edition overrides it
 * with an {@code @Alternative @Priority(1)} bean that inspects the failure (e.g. a PowerStore
 * over-quota {@code 422}) and maps it to a dedicated error.
 */
@ApplicationScoped
public class DefaultCopyFailureClassifier implements CopyFailureClassifier {

  @Override
  public GraphQLError classify(
      Throwable failure,
      String nodeId,
      ResultPath resultPath,
      Supplier<GraphQLError> defaultError) {
    return defaultError.get();
  }
}
