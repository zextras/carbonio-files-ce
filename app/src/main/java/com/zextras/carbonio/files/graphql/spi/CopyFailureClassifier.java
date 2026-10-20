// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.spi;

import com.zextras.carbonio.files.graphql.errors.ErrorCodes;

/**
 * Extension seam for turning a filestore copy/clone failure ({@code copyNodes} / {@code
 * cloneVersion}) into the {@link ErrorCodes} surfaced to the caller — the code-first replacement
 * for the retired graphql-java {@code CopyFailureClassifier}. CE ships a behaviour-preserving
 * {@link DefaultCopyFailureClassifier} that always returns {@link ErrorCodes#NODE_COPY_ERROR} (CE
 * cannot read a PowerStore status). The Advanced edition supplies an
 * {@code @Alternative @Priority(1)} bean (same precedent as the {@code QuotaChecker} seam) that
 * maps a storage over-quota failure (HTTP 422) to {@link ErrorCodes#OVER_QUOTA_REACHED}.
 */
public interface CopyFailureClassifier {

  /**
   * @param failure the exception raised by the filestore copy/clone
   * @return the error code to surface; implementations that cannot classify the failure must return
   *     {@link ErrorCodes#NODE_COPY_ERROR}
   */
  ErrorCodes classify(Throwable failure);
}
