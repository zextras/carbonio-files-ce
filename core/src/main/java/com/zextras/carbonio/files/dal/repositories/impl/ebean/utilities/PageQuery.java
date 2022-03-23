// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import java.util.List;
import java.util.Optional;

/**
 * This class is used as a container for mapping to and from JSON the necessary data to create a
 * pageToken for key-set pagination on findNodes api.
 */
public class PageQuery {

  String             keySet;
  Integer            limit;
  Optional<NodeSort> sort;
  Optional<Boolean>  flagged;
  Optional<String>   folderId;
  Optional<Boolean>  cascade;
  Optional<Boolean>  sharedWithMe;
  Optional<Boolean>  sharedByMe;
  Optional<Boolean>  directShare;
  List<String>       keywords;

  public PageQuery() {}

  public PageQuery(
    String keyset,
    Integer limit,
    String sort,
    Boolean flagged,
    String folderId,
    Boolean cascade,
    Boolean sharedWithMe,
    Boolean sharedByMe,
    Boolean directShare,
    List<String> keywords
  ) {
    setKeyset(keyset);
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

  public String getKeyset() {
    return keySet;
  }

  public void setKeyset(String keyset) {
    this.keySet = keyset;
  }

  public Integer getLimit() {
    return limit;
  }

  public void setLimit(Integer limit) {
    this.limit = limit;
  }

  public String getSort() {
    return sort.map(sort -> sort.toString()).orElse(null);
  }

  public void setSort(String sort) {
    this.sort = Optional.ofNullable(sort).map(NodeSort::valueOf);
  }

  public Boolean getFlagged() {
    return flagged.orElse(null);
  }

  public void setFlagged(Boolean flagged) {
    this.flagged = Optional.ofNullable(flagged);
  }

  public String getFolderId() {
    return folderId.orElse(null);
  }

  public void setFolderId(String folderId) {
    this.folderId = Optional.ofNullable(folderId);
  }

  public Boolean getCascade() {return cascade.orElse(null);}

  public void setCascade(Boolean cascade) {
    this.cascade = Optional.ofNullable(cascade);
  }

  public Boolean getSharedWithMe() {return sharedWithMe.orElse(null);}

  public void setSharedWithMe(Boolean sharedWithMe) {
    this.sharedWithMe = Optional.ofNullable(sharedWithMe);
  }

  public Boolean getSharedByMe() {return sharedByMe.orElse(null);}

  public void setSharedByMe(Boolean sharedByMe) {this.sharedByMe = Optional.ofNullable(sharedByMe);}

  public Boolean getDirectShare() {return directShare.orElse(null);}

  public void setDirectShare(Boolean directShare) {
    this.directShare = Optional.ofNullable(directShare);
  }

  public List<String> getKeywords() {return keywords;}

  public void setKeywords(List<String> keywords) {this.keywords = keywords;}

}
