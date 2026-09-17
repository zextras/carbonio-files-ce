// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.junit.jupiter.api.Test;

/**
 * Trust proof for the comparison mechanism used by {@link SchemaContractTest}. It proves the render
 * pipeline runs standalone (no CDI container) and that canonical comparison is blind to cosmetic
 * noise (P1) yet catches every real contract difference (P2). If any of these regress, the contract
 * gate can no longer be trusted.
 */
class SchemaComparisonTest {

  /**
   * A canonical fixture with an interface, a union, a custom scalar, enums, fields and arguments.
   */
  private static final String BASE =
      """
      scalar BigInteger

      enum Color {
        RED
        GREEN
        BLUE
      }

      interface Node {
        id: ID!
      }

      type User {
        id: ID!
        name: String!
      }

      type Admin {
        id: ID!
        level: Int!
      }

      union Account = User | Admin

      type Thing implements Node {
        id: ID!
        title: String!
        count: Int!
        created_at: BigInteger!
        owner(id: ID!): User
      }

      type Query {
        thing(id: ID!): Thing
        account: Account
        color: Color
      }
      """;

  private static String render(String sdl) {
    return GraphqlSchemaRendering.renderFromSdl(sdl);
  }

  // --- Render pipeline works standalone (SmallRye build + bootstrap, no CDI container) ---------

  @GraphQLApi
  public static class FixtureApi {

    @Query
    public FixtureType currentFixture() {
      return null;
    }
  }

  public static class FixtureType {

    private long size;

    public long getSize() {
      return size;
    }
  }

  @Test
  void renderPipelineBuildsAndBootstrapsWithoutContainer() throws IOException {
    String rendered = GraphqlSchemaRendering.renderFromClasses(FixtureApi.class, FixtureType.class);

    assertThat(rendered).isNotBlank();
    assertThat(rendered).contains("type Query");
    // long -> BigInteger, and the custom scalar is emitted by the printer.
    assertThat(rendered).contains("scalar BigInteger");
  }

  // --- P1: noise-insensitive ------------------------------------------------------------------

  @Test
  void ignoresOrderWhitespaceCommasAndDescriptions() {
    String noisy =
        """
        # a leading comment that must be ignored

        "The query root, described."
        type Query {
          color: Color
          account: Account
          thing(id: ID!): Thing
        }

        union Account = Admin | User

        type Thing implements Node {
          owner(id: ID!,): User
          created_at: BigInteger!,
          count: Int!
          "the human-readable title"
          title: String!
          id: ID!
        }

        interface Node { id: ID! }

        enum Color {
          BLUE
          RED
          GREEN
        }

        type Admin {
          level: Int!
          id: ID!
        }

        type User {
          name: String!
          id: ID!
        }

        scalar BigInteger
        """;

    assertThat(render(noisy)).isEqualTo(render(BASE));
  }

  // --- P2: diff-sensitive (each mutation its own assertion) -----------------------------------

  @Test
  void detectsNullabilityChange() {
    String mutated = BASE.replace("title: String!", "title: String");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }

  @Test
  void detectsChangedFieldReturnType() {
    String mutated = BASE.replace("count: Int!", "count: Float!");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }

  @Test
  void detectsRemovedField() {
    String mutated = BASE.replace("  count: Int!\n", "");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }

  @Test
  void detectsAddedField() {
    String mutated = BASE.replace("  title: String!\n", "  title: String!\n  extra: String\n");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }

  @Test
  void detectsChangedArgumentType() {
    String mutated = BASE.replace("owner(id: ID!)", "owner(id: String!)");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }

  @Test
  void detectsAddedEnumValue() {
    String mutated = BASE.replace("  BLUE\n", "  BLUE\n  YELLOW\n");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }

  @Test
  void detectsRemovedUnionMember() {
    String mutated = BASE.replace("union Account = User | Admin", "union Account = User");
    assertThat(render(mutated)).isNotEqualTo(render(BASE));
  }
}
