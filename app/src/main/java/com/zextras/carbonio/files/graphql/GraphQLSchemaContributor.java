// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

/**
 * CE extension seam supplying extra SDL fragments to be merged into the base schema's {@code
 * TypeDefinitionRegistry} before the executable schema is built. CE ships zero contributors, so the
 * merged schema is identical to the base one. The Advanced edition adds contributors as
 * {@code @ApplicationScoped} beans to extend the GraphQL schema.
 */
public interface GraphQLSchemaContributor {

  /**
   * @return an SDL fragment (type/extend definitions) to merge into the base schema registry.
   */
  String schemaSdl();
}
