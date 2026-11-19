// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

public final class ErrorCodeNames {

  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
  public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
  public static final String NODE_NOT_FOUND = "NODE_NOT_FOUND";
  public static final String FILE_VERSION_NOT_FOUND = "FILE_VERSION_NOT_FOUND";
  public static final String SHARE_NOT_FOUND = "SHARE_NOT_FOUND";
  public static final String SHARE_CREATION_ERROR = "SHARE_CREATION_ERROR";
  public static final String MISSING_FIELD = "MISSING_FIELD";
  public static final String NODE_WRITE_ERROR = "NODE_WRITE_ERROR";
  public static final String NODE_COPY_ERROR = "NODE_COPY_ERROR";
  public static final String NODE_DUPLICATED = "NODE_DUPLICATED";
  public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";
  public static final String VERSIONS_LIMIT_REACHED = "VERSIONS_LIMIT_REACHED";
  public static final String ACCESS_CODE_REQUIRED = "ACCESS_CODE_REQUIRED";
  public static final String WRONG_ACCESS_CODE = "WRONG_ACCESS_CODE";
  public static final String LINK_LIMIT_EXCEEDED = "LINK_LIMIT_EXCEEDED";

  private ErrorCodeNames() {}
}
