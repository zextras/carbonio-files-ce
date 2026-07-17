// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.dao.ebean.Node;

/**
 * CE extension seam invoked after a successful internal / service-account upload (the {@code
 * requesterEntity.isEmpty()} path of {@link BlobService#uploadFile}). CE registers no listeners, so
 * this is a no-op fan-out. The Advanced edition registers a listener (e.g. a recording-notification
 * listener) via any {@code @ApplicationScoped} bean implementing this interface.
 */
public interface UploadCompletionListener {

  /**
   * Notifies that a new node was uploaded by a service account (no user requester entity).
   *
   * @param uploadedNode the newly created node
   * @param destinationFolder the folder the node was uploaded into
   * @param requesterId the id of the (service-account) requester that performed the upload
   */
  void onUploadCompleted(Node uploadedNode, Node destinationFolder, String requesterId);
}
