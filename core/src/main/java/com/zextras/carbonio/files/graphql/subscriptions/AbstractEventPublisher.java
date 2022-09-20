// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.subscriptions;

import com.google.common.util.concurrent.AbstractService;
import com.zextras.carbonio.files.graphql.types.EventType;
import com.zextras.carbonio.files.graphql.types.GraphQLEvent;
import graphql.execution.DataFetcherResult;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Map;

public abstract class AbstractEventPublisher<T extends GraphQLEvent>
  extends AbstractService implements EventPublisher<T> {

  private final EventType           eventType;
  private       PublishProcessor<T> source;
  private       Flowable<T>         publisher;
  private       Disposable          disposable;

  public AbstractEventPublisher(EventType eventType) {
    this.eventType = eventType;
  }

  public Flowable<T> getPublisher() {
    return publisher;
  }

  public void sendEvent(T event) {
    if (isRunning() && !disposable.isDisposed()) {
      source.onNext(event);
    }
  }

  @Override
  public void sendEvent(DataFetcherResult<Map<String, Object>> dataFetcherResult) {
    throw new UnsupportedOperationException();
  }

  public EventType getEventType() {
    return eventType;
  }

  @Override
  protected void doStart() {
    if (!isRunning()) {
      source = PublishProcessor.create();

      publisher = source
        .onBackpressureBuffer()
        .subscribeOn(Schedulers.computation(), false)
        .observeOn(Schedulers.computation(), false);

      disposable = publisher
        .publish()
        .connect();

      notifyStarted();
    }
  }

  @Override
  protected void doStop() {
    if (!disposable.isDisposed()) {
      disposable.isDisposed();
    }

    if (isRunning()) {
      notifyStopped();
    }
  }
}
