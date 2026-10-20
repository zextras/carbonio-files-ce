// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import java.util.Map;

public class InputValidationException extends FilesGraphQLException {

  public InputValidationException(String message) {
    super(null, message, Map.of());
  }
}
