// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.graphql.api.PublicApi;
import jakarta.annotation.security.PermitAll;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.eclipse.microprofile.graphql.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicOperationGateTest {

  @Test
  void publicRootFieldsAreExactlyThePermitAllQueriesPlusTypename() {
    var permitAllQueries =
        Arrays.stream(PublicApi.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(PermitAll.class))
            .map(m -> m.getAnnotation(Query.class).value())
            .collect(Collectors.toSet());
    permitAllQueries.add("__typename");

    assertThat(PublicOperationGate.PUBLIC_ROOT_FIELDS).isEqualTo(permitAllQueries);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "query { getPublicNode(node_link_id: \"x\") { id } }",
        "{ getPublicNode(node_link_id: \"x\") { id } }",
        "query q($l: String!) { a: getPublicNode(node_link_id: $l) { id } __typename }",
        "query { findPublicNodes(folder_id: \"f\", node_link_id: \"x\") { nodes { id } } }",
        "query { ... on Query { getPublicNode(node_link_id: \"x\") { id } } }",
        "query { ...P } fragment P on Query { ...Q } fragment Q on Query {"
            + " getPublicNode(node_link_id: \"x\") { id } }",
        "query { ...P } fragment P on Query { ...P getPublicNode(node_link_id: \"x\") { id } }"
      })
  void documentsSelectingOnlyPublicFieldsArePublic(String query) {
    assertThat(PublicOperationGate.isPublicOnly(query, null)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "query { getRootsList { id } }",
        "query { getPublicNode: getRootsList { id } }",
        "query { getPublicNode(node_link_id: \"x\") { id } getRootsList { id } }",
        "query { ... on Query { getRootsList { id } } }",
        "query { ...P } fragment P on Query { getRootsList { id } }",
        "query { ...Missing }",
        "mutation { getPublicNode(node_link_id: \"x\") { id } }",
        "query a { getPublicNode(node_link_id: \"x\") { id } } query b { getRootsList { id } }",
        "query { __schema { queryType { name } } }",
        "query { getPublicNode(node_link_id: \"x\") { id }",
        ""
      })
  void anythingElseIsNotPublic(String query) {
    assertThat(PublicOperationGate.isPublicOnly(query, null)).isFalse();
  }

  @Test
  void theOperationNamedByTheRequestDecides() {
    String document =
        "query pub { getPublicNode(node_link_id: \"x\") { id } } query priv { getRootsList { id }"
            + " }";

    assertThat(PublicOperationGate.isPublicOnly(document, "pub")).isTrue();
    assertThat(PublicOperationGate.isPublicOnly(document, "priv")).isFalse();
    assertThat(PublicOperationGate.isPublicOnly(document, "missing")).isFalse();
    assertThat(PublicOperationGate.isPublicOnly(document, "")).isTrue();
    assertThat(
            PublicOperationGate.isPublicOnly(
                "query priv { getRootsList { id } } query pub { getPublicNode(node_link_id: \"x\")"
                    + " { id } }",
                ""))
        .isFalse();
  }
}
