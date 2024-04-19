// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * This class is used as a container for mapping to and from JSON the necessary data to create a
 * pageToken for key-set pagination on findNodes api.
 */
public class PageQuery {

  private Integer limit;
  private List<String> keywords;
  private Optional<String> keySet;
  private Optional<NodeSort> sort;
  private Optional<Boolean> flagged;
  private Optional<String> folderId;
  private Optional<Boolean> cascade;
  private Optional<Boolean> sharedWithMe;
  private Optional<Boolean> sharedByMe;
  private Optional<Boolean> directShare;
  private Optional<NodeType> nodeType;
  private Optional<String> ownerId;

  public PageQuery() {
    limit = Files.Config.Pagination.LIMIT;
    keywords = Collections.emptyList();
    keySet = Optional.empty();
    sort = Optional.empty();
    flagged = Optional.empty();
    folderId = Optional.empty();
    cascade = Optional.empty();
    sharedWithMe = Optional.empty();
    sharedByMe = Optional.empty();
    directShare = Optional.empty();
    nodeType = Optional.empty();
    ownerId = Optional.empty();
  }

  public PageQuery(
      String keySet,
      Integer limit,
      String sort,
      Boolean flagged,
      String folderId,
      Boolean cascade,
      Boolean sharedWithMe,
      Boolean sharedByMe,
      Boolean directShare,
      List<String> keywords) {
    setKeySet(keySet);
    setLimit(limit);
    setSort(sort);
    setFlagged(flagged);
    setFolderId(folderId);
    setCascade(cascade);
    setSharedWithMe(sharedWithMe);
    setSharedByMe(sharedByMe);
    setDirectShare(directShare);
    setKeywords(keywords);
  }

  public Optional<String> getKeySet() {
    return keySet;
  }

  public PageQuery setKeySet(String keySet) {
    this.keySet = Optional.of(keySet);
    return this;
  }

  public Integer getLimit() {
    return limit;
  }

  public PageQuery setLimit(Integer limit) {
    this.limit = limit;
    return this;
  }

  public Optional<String> getSort() {
    return sort.map(Enum::toString);
  }

  public void setSort(String sort) {
    this.sort = Optional.ofNullable(sort).map(NodeSort::valueOf);
  }

  public Optional<Boolean> getFlagged() {
    return flagged;
  }

  public void setFlagged(Boolean flagged) {
    this.flagged = Optional.ofNullable(flagged);
  }

  public Optional<String> getFolderId() {
    return folderId;
  }

  public void setFolderId(String folderId) {
    this.folderId = Optional.ofNullable(folderId);
  }

  public Optional<Boolean> getCascade() {
    return cascade;
  }

  public void setCascade(Boolean cascade) {
    this.cascade = Optional.ofNullable(cascade);
  }

  public Optional<Boolean> getSharedWithMe() {
    return sharedWithMe;
  }

  public void setSharedWithMe(Boolean sharedWithMe) {
    this.sharedWithMe = Optional.ofNullable(sharedWithMe);
  }

  public Optional<Boolean> getSharedByMe() {
    return sharedByMe;
  }

  public void setSharedByMe(Boolean sharedByMe) {
    this.sharedByMe = Optional.ofNullable(sharedByMe);
  }

  public Optional<Boolean> getDirectShare() {
    return directShare;
  }

  public void setDirectShare(Boolean directShare) {
    this.directShare = Optional.ofNullable(directShare);
  }

  public List<String> getKeywords() {
    return keywords;
  }

  public void setKeywords(List<String> keywords) {
    this.keywords = keywords;
  }

  public Optional<NodeType> getNodeType() {
    return nodeType;
  }

  public void setNodeType(NodeType nodeType) {
    this.nodeType = Optional.ofNullable(nodeType);
  }

  public Optional<String> getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = Optional.ofNullable(ownerId);
  }

  public static PageQuery fromToken(String token) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      return mapper.readValue(new String(Base64.getDecoder().decode(token)), PageQuery.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public String toToken() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.registerModule(new Jdk8Module());
    try {
      return Base64.getEncoder().encodeToString(mapper.writeValueAsString(this).getBytes());
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
