// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validators;

import graphql.execution.instrumentation.fieldvalidation.FieldAndArguments;
import graphql.execution.instrumentation.fieldvalidation.FieldValidationEnvironment;

/**
 * Guice's factory to create a {@link GenericControllerEvaluator}.
 */
public interface GenericControllerEvaluatorFactory {

  GenericControllerEvaluator create(
    FieldAndArguments fieldAndArguments,
    FieldValidationEnvironment environment
  );
}
