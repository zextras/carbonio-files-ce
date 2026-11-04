// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

/** The identity of one blob in the external store, within an owner. */
public record BlobKey(String nodeId, int version) {}
