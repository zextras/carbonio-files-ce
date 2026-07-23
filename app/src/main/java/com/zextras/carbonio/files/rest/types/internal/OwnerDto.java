// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/**
 * The owner of a node, as exposed by the trusted {@code /internal} node endpoints.
 *
 * <p>Only the id is exposed: {@code full_name} was verified to be dead code in the only consumer
 * (docs-connector) and is intentionally omitted here. If it is ever needed, it can be resolved via
 * {@code UserRepository#getUserById} — a cross-service call to user-management — rather than
 * always paying that cost for a field nobody reads.
 */
public record OwnerDto(String id) {}
