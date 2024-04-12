package com.zextras.carbonio.files.api.utilities.entities;

import com.zextras.carbonio.files.dal.dao.ebean.NodeType;

public class PopulatorNode{
  String nodeId;
  String creatorId;
  String ownerId;
  String parentId;
  String name;
  String description;
  NodeType type;
  String ancestorIds;
  Long size;
  String mimeType;

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
  }

  public String getCreatorId() {
    return creatorId;
  }

  public void setCreatorId(String creatorId) {
    this.creatorId = creatorId;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getParentId() {
    return parentId;
  }

  public void setParentId(String parentId) {
    this.parentId = parentId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public NodeType getType() {
    return type;
  }

  public void setType(NodeType type) {
    this.type = type;
  }

  public String getAncestorIds() {
    return ancestorIds;
  }

  public void setAncestorIds(String ancestorIds) {
    this.ancestorIds = ancestorIds;
  }

  public Long getSize() {
    return size;
  }

  public void setSize(Long size) {
    this.size = size;
  }

  public String getMimeType() {
    return mimeType;
  }

  public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

  public PopulatorNode(String nodeId, String creatorId, String ownerId, String parentId, String name,
                       String description, NodeType type, String ancestorIds, Long size, String mimeType) {

    if(type.equals(NodeType.FOLDER) && mimeType != null){
      throw new IllegalArgumentException("Node type is folder but mime type is not null");
    }

    if(!type.equals(NodeType.FOLDER) && mimeType == null){
      throw new IllegalArgumentException("Node type is not folder but mime type is null");
    }

    this.nodeId = nodeId;
    this.creatorId = creatorId;
    this.ownerId = ownerId;
    this.parentId = parentId;
    this.name = name;
    this.description = description;
    this.type = type;
    this.ancestorIds = ancestorIds;
    this.size = size;
    this.mimeType = mimeType;
  }
}

