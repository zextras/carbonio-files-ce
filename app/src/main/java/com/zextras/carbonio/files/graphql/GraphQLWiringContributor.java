// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import graphql.schema.idl.RuntimeWiring;

/**
 * CE extension seam applied to the {@link RuntimeWiring.Builder} AFTER all of CE's base wiring has
 * been registered, so contributors can add data fetchers / type resolvers for the types they
 * introduce via a {@link GraphQLSchemaContributor}. CE ships zero contributors, so the wiring is
 * identical to the base one. The Advanced edition adds contributors as {@code @ApplicationScoped}
 * beans.
 */
public interface GraphQLWiringContributor {

  /**
   * Contributes additional wiring on top of CE's base wiring.
   *
   * @param builder the runtime-wiring builder already populated with CE's base wiring
   */
  void contribute(RuntimeWiring.Builder builder);

  /**
   * Scope flag: whether this contributor targets the public ({@code /public/graphql}) schema rather
   * than the authenticated one. Defaults to {@code false} (authenticated schema), since CE and most
   * Advanced contributors extend the authenticated API only.
   *
   * @return {@code true} if {@link #contribute(RuntimeWiring.Builder)} should be applied to the
   *     public wiring instead of the authenticated one.
   */
  default boolean appliesToPublicSchema() {
    return false;
  }
}
