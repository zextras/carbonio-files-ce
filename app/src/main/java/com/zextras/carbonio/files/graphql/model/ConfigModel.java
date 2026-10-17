// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("Config")
public class ConfigModel {

  private final String name;
  private final String value;

  public ConfigModel(String name, String value) {
    this.name = name;
    this.value = value;
  }

  @NonNull
  public String getName() {
    return name;
  }

  public String getValue() {
    return value;
  }
}
