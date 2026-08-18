// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.systemd;

import com.zextras.carbonio.systemd.SystemdNotify;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class SystemdReadinessNotifier {

  void onStart(@Observes StartupEvent ev) {
    SystemdNotify.ready("carbonio-files-ce ready");
  }
}
