// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import java.util.List;

/** The users whose unread counter must be bumped once, in sorted order, after a commit. */
public record UnreadNotifications(List<String> userIds) {}
