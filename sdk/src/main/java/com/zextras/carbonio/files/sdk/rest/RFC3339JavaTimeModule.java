// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.sdk.rest;

import com.fasterxml.jackson.databind.module.SimpleModule;

// Empty stand-in for the generated RFC3339JavaTimeModule (ignored via .openapi-generator-ignore):
// the /internal DTOs carry no java.time fields, so no deserializers are needed. Avoids the generated
// RFC3339InstantDeserializer, which references jackson 2.16's JavaTimeFeature and would break this
// SDK on carbonio-mailbox's jackson 2.15. The rest of the generated client (ApiClient/*Api/DTOs) is kept.
public class RFC3339JavaTimeModule extends SimpleModule {
  private static final long serialVersionUID = 1L;

  public RFC3339JavaTimeModule() {
    super("RFC3339JavaTimeModule");
  }
}
