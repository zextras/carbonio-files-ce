// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.interfaces;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;

public interface FilesConsumer extends Consumer {
  String getNameOfQueue();
  Channel getChannel();
}
