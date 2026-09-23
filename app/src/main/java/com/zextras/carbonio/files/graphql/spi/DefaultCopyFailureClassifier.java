// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.spi;

import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CE default for {@link CopyFailureClassifier}: CE cannot read a PowerStore status, so every
 * copy/clone failure maps to {@link ErrorCodes#NODE_COPY_ERROR}. The Advanced edition overrides
 * this with an {@code @Alternative @Priority(1)} bean.
 */
@ApplicationScoped
public class DefaultCopyFailureClassifier implements CopyFailureClassifier {

  @Override
  public ErrorCodes classify(Throwable failure) {
    return ErrorCodes.NODE_COPY_ERROR;
  }
}
