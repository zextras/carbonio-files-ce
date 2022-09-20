// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.subscriptions;

import com.zextras.carbonio.files.graphql.types.EventType;
import com.zextras.carbonio.files.graphql.types.GraphQLEvent;
import graphql.execution.DataFetcherResult;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;

public interface EventPublisher<T extends GraphQLEvent> {

  void sendEvent(T event);

  void sendEvent(DataFetcherResult<Map<String, Object>> dataFetcherResult);

  EventType getEventType();

  Flowable<T> getPublisher();
}
