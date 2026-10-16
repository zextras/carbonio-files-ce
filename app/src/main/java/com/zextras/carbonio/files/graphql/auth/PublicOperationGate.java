// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import com.zextras.carbonio.files.Constants.API.Headers;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Public operations carry no authentication: a request whose GraphQL document selects only public
 * root fields never resolves the {@code ZM_AUTH_TOKEN} cookie, whatever the client sends. Any other
 * request with a cookie is authenticated here, before execution, so 401/403 keep their HTTP status
 * and body. Authorization itself stays on the {@code @Authenticated}/{@code @PermitAll} APIs.
 */
@ApplicationScoped
public class PublicOperationGate {

  static final Set<String> PUBLIC_ROOT_FIELDS =
      Set.of("getPublicNode", "findPublicNodes", "__typename");

  public void register(@Observes Router router) {
    router.post("/graphql").order(-199).handler(this::handle);
    router.post("/graphql/").order(-199).handler(this::handle);
  }

  void handle(RoutingContext ctx) {
    if (ctx.request().getCookie(Headers.COOKIE_ZM_AUTH_TOKEN) == null || isPublicOnly(ctx.body())) {
      ctx.next();
      return;
    }
    QuarkusHttpUser.getSecurityIdentity(ctx, null)
        .subscribe()
        .with(
            identity -> ctx.next(),
            failure -> {
              if (ctx.get(QuarkusHttpUser.AUTH_FAILURE_HANDLER) == null) {
                ctx.fail(failure);
              }
            });
  }

  private static boolean isPublicOnly(RequestBody body) {
    JsonObject json;
    try {
      json = body.asJsonObject();
    } catch (RuntimeException e) {
      return false;
    }
    if (json == null || !(json.getValue("query") instanceof String query)) {
      return false;
    }
    return isPublicOnly(query, json.getValue("operationName") instanceof String name ? name : null);
  }

  static boolean isPublicOnly(String query, String operationName) {
    Document document;
    try {
      document = Parser.parse(query);
    } catch (RuntimeException e) {
      return false;
    }
    OperationDefinition operation =
        selectOperation(document.getDefinitionsOfType(OperationDefinition.class), operationName);
    if (operation == null || operation.getOperation() != OperationDefinition.Operation.QUERY) {
      return false;
    }
    Map<String, FragmentDefinition> fragments =
        document.getDefinitionsOfType(FragmentDefinition.class).stream()
            .collect(
                Collectors.toMap(FragmentDefinition::getName, Function.identity(), (a, b) -> a));
    return selectsOnlyPublicFields(operation.getSelectionSet(), fragments, new HashSet<>());
  }

  private static OperationDefinition selectOperation(
      List<OperationDefinition> operations, String operationName) {
    if (operationName != null && !operationName.isEmpty()) {
      return operations.stream()
          .filter(operation -> operationName.equals(operation.getName()))
          .findFirst()
          .orElse(null);
    }
    return operations.size() == 1 ? operations.get(0) : null;
  }

  private static boolean selectsOnlyPublicFields(
      SelectionSet selectionSet, Map<String, FragmentDefinition> fragments, Set<String> visited) {
    if (selectionSet == null) {
      return false;
    }
    for (Selection<?> selection : selectionSet.getSelections()) {
      boolean isPublic =
          switch (selection) {
            case Field field -> PUBLIC_ROOT_FIELDS.contains(field.getName());
            case InlineFragment inline ->
                selectsOnlyPublicFields(inline.getSelectionSet(), fragments, visited);
            case FragmentSpread spread -> {
              FragmentDefinition fragment = fragments.get(spread.getName());
              yield fragment != null
                  && (!visited.add(spread.getName())
                      || selectsOnlyPublicFields(fragment.getSelectionSet(), fragments, visited));
            }
            default -> false;
          };
      if (!isPublic) {
        return false;
      }
    }
    return true;
  }
}
