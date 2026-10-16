// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.junit.jupiter.api.Test;

/**
 * {@link GraphQLExecutionRoute} terminates the request context as soon as the execution returns: a
 * resolver completing later would run without it.
 */
class SynchronousResolversTest {

  @Test
  void noGraphQLApiMethodReturnsAnAsynchronousType() {
    List<String> apis = new ArrayList<>();
    List<String> asynchronous = new ArrayList<>();
    for (JavaClass javaClass :
        new ClassFileImporter().importPackages("com.zextras.carbonio.files")) {
      if (!javaClass.isAnnotatedWith(GraphQLApi.class)) {
        continue;
      }
      apis.add(javaClass.getSimpleName());
      for (JavaMethod method : javaClass.getAllMethods()) {
        if (method.getModifiers().contains(JavaModifier.PUBLIC)
            && Stream.of(Uni.class, Multi.class, CompletionStage.class, CompletableFuture.class)
                .anyMatch(method.getRawReturnType()::isAssignableTo)) {
          asynchronous.add(javaClass.getSimpleName() + "#" + method.getName());
        }
      }
    }

    assertThat(apis).contains("NodeApi", "PublicApi", "ShareApi");
    assertThat(asynchronous).isEmpty();
  }
}
