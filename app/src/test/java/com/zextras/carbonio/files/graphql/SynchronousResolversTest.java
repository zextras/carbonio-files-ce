// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.graphql.api.NodeApi;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
    for (Class<?> api : TestUtils.annotatedClassesBeside(NodeApi.class, GraphQLApi.class)) {
      apis.add(api.getSimpleName());
      for (Method method : api.getMethods()) {
        if (Stream.of(Uni.class, Multi.class, CompletionStage.class)
            .anyMatch(type -> type.isAssignableFrom(method.getReturnType()))) {
          asynchronous.add(api.getSimpleName() + "#" + method.getName());
        }
      }
    }

    assertThat(apis).contains("NodeApi", "PublicApi", "ShareApi");
    assertThat(asynchronous).isEmpty();
  }
}
