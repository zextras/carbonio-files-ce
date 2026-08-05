// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import graphql.execution.instrumentation.fieldvalidation.SimpleFieldValidation;

/**
 * CE extension seam applied to the {@link SimpleFieldValidation} of the authenticated
 * {@link GraphQLProvider} AFTER all of CE's base pre-execution field-validation rules have been
 * registered, so contributors can validate the arguments of the queries/mutations they introduce
 * via a {@link GraphQLSchemaContributor}. CE ships zero contributors, so the validation is identical
 * to the base one. The Advanced edition adds contributors as {@code @ApplicationScoped} beans. The
 * public schema ({@link PublicGraphQLProvider}) has no field validation, so there is no public-scope
 * variant of this seam.
 */
public interface GraphQLFieldValidationContributor {

  /**
   * Contributes additional field-validation rules on top of CE's base rules.
   *
   * @param validation the field-validation container already populated with CE's base rules; add
   *     rules via {@link SimpleFieldValidation#addRule}
   */
  void contribute(SimpleFieldValidation validation);
}
