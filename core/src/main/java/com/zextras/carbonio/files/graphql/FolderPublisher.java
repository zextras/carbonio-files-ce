// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.graphql.types.NodeEvent;
import com.zextras.carbonio.files.graphql.types.NodeEvent.NodeEventType;
import graphql.execution.DataFetcherResult;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.observables.ConnectableObservable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public class FolderPublisher {

  private final Flowable<NodeEvent> pub;
  private List<NodeEvent>           list;

  @Inject
  public FolderPublisher() {
    list = new ArrayList<>();
    Observable<NodeEvent> stockPriceUpdateObservable = Observable.create(emitter -> {

      ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
      executorService.scheduleAtFixedRate(() -> newStockTick(emitter), 0, 2, TimeUnit.SECONDS);

    });

    ConnectableObservable<NodeEvent> connectableObservable = stockPriceUpdateObservable.share().publish();
    connectableObservable.connect();

    pub = connectableObservable.toFlowable(BackpressureStrategy.BUFFER);
  }

  private void newStockTick(ObservableEmitter<NodeEvent> emitter) {

    list.forEach(emitter::onNext);
    list.clear();
  }

  public void addMap(Map<String, Object> map, NodeEventType action){

    NodeEvent c = new NodeEvent(action, map);
    list.add(c);
  }

  public void addMap(DataFetcherResult dfr, NodeEventType action){

    if(dfr.hasErrors())
      return;

    NodeEvent c = new NodeEvent(action, (Map<String, Object>) dfr.getData());
    list.add(c);
  }

  public Flowable<NodeEvent> getPub(String parentId, String requesterId) {
    return parentId.equals("LOCAL_ROOT")
      ? pub.filter(nodeEvent -> nodeEvent.getOwner().equals(requesterId))
      : pub.filter(nodeEvent -> parentId.equals(nodeEvent.getParentId()));
  }

  /*
  public void test() {
    String owner = UUID.randomUUID().toString();

    Observable<Node> observable = Observable.just(new Node(
      UUID.randomUUID().toString(),
      owner,
      owner,
      "LOCAL_ROOT",
      System.currentTimeMillis(),
      System.currentTimeMillis(),
      "testing folder",
      "",
      NodeType.FOLDER,
      "LOCAL_ROOT",
      0L
    ));


    Observable.create( sub -> {
      sub.
    });

  }

   */

}
