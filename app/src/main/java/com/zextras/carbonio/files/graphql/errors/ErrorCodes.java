// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

public enum ErrorCodes {
  UNAUTHENTICATED,
  ACCOUNT_NOT_FOUND,
  NODE_NOT_FOUND,
  FILE_VERSION_NOT_FOUND,
  SHARE_NOT_FOUND,
  SHARE_CREATION_ERROR,
  MISSING_FIELD,
  NODE_WRITE_ERROR,
  NODE_COPY_ERROR,
  NODE_DUPLICATED,
  LINK_NOT_FOUND,
  VERSIONS_LIMIT_REACHED,
  ACCESS_CODE_REQUIRED,
  WRONG_ACCESS_CODE,
  LINK_LIMIT_EXCEEDED,
  // Never produced by CE (no quota concept). Reserved for the Advanced edition's
  // CopyFailureClassifier
  // seam, which maps a storage over-quota (Powerstore HTTP 422) during copy/clone to this code so
  // the
  // frontend can show the over-quota UX. Not part of the GraphQL schema (error codes are
  // extensions).
  OVER_QUOTA_REACHED,
}
