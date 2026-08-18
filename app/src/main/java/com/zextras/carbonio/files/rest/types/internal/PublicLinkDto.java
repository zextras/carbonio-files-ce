// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/** The public URL of a newly created link, returned by {@code POST /internal/links}. */
public record PublicLinkDto(String url) {}
