// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;


import com.google.common.net.MediaType;

/**
 * This enumeration is used to keep and manage the specific types of a node
 */
public enum NodeType {
  IMAGE,
  VIDEO,
  AUDIO,
  TEXT,
  SPREADSHEET,
  PRESENTATION,
  FOLDER,
  APPLICATION,
  MESSAGE,
  ROOT,
  OTHER;

  public static NodeType getNodeType(MediaType mimeType) {
    NodeType nodeType;
    switch (mimeType.type()) {
      case "image":
        nodeType = NodeType.IMAGE;
        break;
      case "audio":
        nodeType = NodeType.AUDIO;
        break;
      case "video":
        nodeType = NodeType.VIDEO;
        break;
      case "text":
        nodeType = NodeType.TEXT;
        break;
      case "application":
        switch (mimeType.subtype()) {
          case "vnd.ms-powerpoint":
          case "vnd.openxmlformats-officedocument.presentationml.presentation":
          case "vnd.oasis.opendocument.presentation":
            nodeType = NodeType.PRESENTATION;
            break;
          case "vnd.ms-excel":
          case "vnd.openxmlformats-officedocument.spreadsheetml.sheet":
          case "vnd.oasis.opendocument.spreadsheet":
            nodeType = NodeType.SPREADSHEET;
            break;
          case "msword":
          case "vnd.openxmlformats-officedocument.wordprocessingml.document":
          case "vnd.oasis.opendocument.text":
          case "x-abiword":
          case "pdf":
          case "rtf":
            nodeType = NodeType.TEXT;
            break;
          case "vnd.microsoft.portable-executable":
          case "exe":
          case "dos-exe":
          case "msdos-windows":
          case "x-msdownload":
          case "x-exe":
          case "x-msdos-program":
          case "bat":
          case "x-bat":
          case "x-sh":
          case "x-shar":
          case "vnd.debian.binary-package":
          case "x-msi":
            nodeType = NodeType.APPLICATION;
            break;
          case "vnd.ms-outlook":
          case "mbox":
            nodeType = NodeType.MESSAGE;
            break;
          default:
            nodeType = NodeType.OTHER;
        }
        break;
      default:
        nodeType = NodeType.OTHER;
    }
    return nodeType;
  }
}
