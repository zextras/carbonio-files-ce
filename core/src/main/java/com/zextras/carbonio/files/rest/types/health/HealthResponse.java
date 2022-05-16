// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.health;

import java.util.List;

public class HealthResponse {

  private boolean             ready;
  private List<ServiceHealth> dependencies;

  public boolean isReady() {
    return ready;
  }

  public HealthResponse setReady(boolean ready) {
    this.ready = ready;
    return this;
  }

  public List<ServiceHealth> getDependencies() {
    return dependencies;
  }

  public HealthResponse setDependencies(List<ServiceHealth> dependencies) {
    this.dependencies = dependencies;
    return this;
  }
}
