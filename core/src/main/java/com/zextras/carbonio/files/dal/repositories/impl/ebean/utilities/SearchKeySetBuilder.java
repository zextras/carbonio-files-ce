package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;


import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import org.checkerframework.checker.units.qual.C;

import javax.swing.*;
import java.util.Optional;

//TODO add UT
public class SearchKeySetBuilder {

  Optional<NodeSort> sort;
  Node node;

  public static SearchKeySetBuilder aSearchKeySetBuilder(){
    return new SearchKeySetBuilder();
  }

  public SearchKeySetBuilder withNodeSort(Optional<NodeSort> sort){
    this.sort = sort;
    return this;
  }

  public SearchKeySetBuilder fromNode(Node node){
    this.node = node;
    return this;
  }

  private static String orderToSimbol(SortOrder order){
    if(order.equals(SortOrder.ASCENDING)) return ">";
    else if(order.equals(SortOrder.DESCENDING)) return "<";
    else return "=";
  }

  public String build(){

    sort.ifPresentOrElse(
        s -> {
          if(s.getName().equals(Files.Db.Node.SIZE)){
            new CompareExpression(s.getName(), orderToSimbol(s.getOrder()), String.valueOf(node.getSize()));
          } else {

          }
        },
        () -> {
          //when sort is not present
        });
  }
}
