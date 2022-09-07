// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.observables.ConnectableObservable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public class NodePublisher {

  private final Flowable<Map<String, Object>> pub;
  private List<Map<String, Object>> list;

  public NodePublisher() {
    list = new ArrayList<>();
    Observable<Map<String, Object>> stockPriceUpdateObservable = Observable.create(emitter -> {

      ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
      executorService.scheduleAtFixedRate(() -> newStockTick(emitter), 0, 2, TimeUnit.SECONDS);

    });

    ConnectableObservable<Map<String, Object>> connectableObservable = stockPriceUpdateObservable.share().publish();
    connectableObservable.connect();

    pub = connectableObservable.toFlowable(BackpressureStrategy.BUFFER);
  }

  private void newStockTick(ObservableEmitter<Map<String, Object>> emitter) {

    list.forEach(l -> emitter.onNext(l));
    list.clear();
  }

  public void addMap(Map<String, Object> map){
    list.add(map);
  }
  /*
  public void addNode(Node node) {
    Map<String, String> map = new HashMap<>();

    map.put("node_id", node.getId());
    map.put("id", node.getId());
    map.put("name", node.getName());
    map.put("parent_id", node.getParentId().orElse(""));
    list.add(map);
  }

   */

  public Flowable<Map<String, Object>> getPub(String parentId) {
    return pub.filter(map -> parentId.equals(map.get("parent_id")));
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
