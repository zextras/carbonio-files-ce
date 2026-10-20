// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.graphql.api.NodeApi;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.junit.jupiter.api.Test;

class GraphQLApiAccessTest {

  @Test
  void onlyThePublicQueriesAreReachableWithoutAuthentication() {
    Set<String> permitAll = new HashSet<>();
    List<String> unprotected = new ArrayList<>();
    int rootFields = 0;

    for (Class<?> api : TestUtils.annotatedClassesBeside(NodeApi.class, GraphQLApi.class)) {
      for (Method method : api.getDeclaredMethods()) {
        boolean rootField =
            method.isAnnotationPresent(Query.class) || method.isAnnotationPresent(Mutation.class);
        if (!Modifier.isPublic(method.getModifiers()) || !rootField) {
          continue;
        }
        rootFields++;
        if (method.isAnnotationPresent(PermitAll.class)) {
          permitAll.add(api.getSimpleName() + "#" + method.getName());
        } else if (!method.isAnnotationPresent(Authenticated.class)
            && !api.isAnnotationPresent(Authenticated.class)) {
          unprotected.add(api.getSimpleName() + "#" + method.getName());
        }
      }
    }

    assertThat(rootFields).as("query and mutation root fields inspected").isPositive();
    assertThat(permitAll)
        .containsExactlyInAnyOrder("PublicApi#getPublicNode", "PublicApi#findPublicNodes");
    assertThat(unprotected).isEmpty();
  }
}
