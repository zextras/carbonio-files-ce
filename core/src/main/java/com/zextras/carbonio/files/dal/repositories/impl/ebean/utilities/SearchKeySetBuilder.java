package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;

public class SearchKeySetBuilder {

  private NodeSort nodeSort;

  public static SearchKeySetBuilder aKeySetBuilder() {
    return new SearchKeySetBuilder();
  }

  public SearchKeySetBuilder withNodeCategory(NodeCategory nodeCategory) {
    return this;
  }

  public SearchKeySetBuilder withNodeId(String nodeId) {
    return this;
  }

  public SearchKeySetBuilder withSortFromValue(NodeSort nodeSort, String value) {
    this.nodeSort = nodeSort;
    return this;
  }

  public SearchKeySetBuilder withNode(Node node) {
    return this;
  }

  public SearchKeySetBuilder expGeneric(String sort, String order, String value) {

    return this;
  }

  public SearchKeySetBuilder expGeneri(String sort, String order, String value) {

    return this;
  }

  public SearchKeySetBuilder expMinore(String sort, String value) {

    return this;
  }

  public SearchKeySetBuilder expEquals(String sort, String value) {

    return this;
  }

  public SearchKeySetBuilder expMaggiore(String sort, String value) {

    return this;
  }

  public SearchKeySetBuilder or() {

    return this;
  }

  public SearchKeySetBuilder endOr() {

    return this;
  }

  public SearchKeySetBuilder and() {

    return this;
  }

  public SearchKeySetBuilder endAnd() {

    return this;
  }

  public SearchKeySetBuilder or(SearchKeySetBuilder e) {

    return this;
  }

  public SearchKeySetBuilder and(SearchKeySetBuilder e) {

    return this;
  }

  public void build() {
    // if size
    expMinore("size", "").or().expEquals("size", "").expMaggiore("node_id", "");

    // sort != size
    expGeneric(NodeSort.TYPE_ASC.getType(), ">", "")
        .or()
        .expGeneric(NodeSort.TYPE_ASC.getType(), "=", "")
        .and()
        .expGeneric(nodeSort.getType(), nodeSort.getOrder(), "")
        .endAnd()
        .endOr()
        .or()
        .expGeneric(NodeSort.TYPE_ASC.getType(), "=", "")
        .and()
        .expGeneric(nodeSort.getType(), "=", "")
        .and()
        .expGeneric("node_id", ">", "")
        .endAnd()
        .endOr();

    expGeneric(NodeSort.TYPE_ASC.getType(), ">", "")
        .or(
            expGeneric(NodeSort.TYPE_ASC.getType(), "=", "")
                .and()
                .expGeneric(nodeSort.getType(), nodeSort.getOrder(), "")
                .endAnd())
        .or(
            expGeneric(NodeSort.TYPE_ASC.getType(), "=", "")
                .and()
                .expGeneric(nodeSort.getType(), "=", "")
                .and()
                .expGeneric("node_id", ">", "")
                .endAnd());
    /*
    .endAnd()
    .endOr()
    .or()
    .expGeneric(NodeSort.TYPE_ASC.getType(), "=", "")
    .and()
    .expGeneric(nodeSort.getType(), "=", "")
    .and()
    .expGeneric("node_id", ">", "")
    .endAnd()
    .endOr();
    ()*/

    // no sort

  }
}
