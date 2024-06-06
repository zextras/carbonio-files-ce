// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import java.io.IOException;

public interface MessageBrokerManager {
  public void startAllConsumers() throws RuntimeException;

}
