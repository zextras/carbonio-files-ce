// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import graphql.language.SourceLocation;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.ParserOptions;
import graphql.parser.ParsingListener;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

// SmallRye turns a quoted BigInteger literal into a BigDecimal before bounding it (R23).
@ApplicationScoped
public class InlineLiteralLengthLimit {

  static final int MAX_INLINE_LITERAL_CHARACTERS = 8192;

  void onStart(@Observes StartupEvent event) {
    ParserOptions.setDefaultOperationParserOptions(
        ParserOptions.getDefaultOperationParserOptions()
            .transform(builder -> builder.parsingListener(InlineLiteralLengthLimit::check)));
  }

  private static void check(ParsingListener.Token token) {
    String text = token.getText();
    if (text != null && text.length() > MAX_INLINE_LITERAL_CHARACTERS) {
      throw new OversizedLiteralException(token);
    }
  }

  static final class OversizedLiteralException extends InvalidSyntaxException {
    OversizedLiteralException(ParsingListener.Token token) {
      super(
          "Inline literal longer than " + MAX_INLINE_LITERAL_CHARACTERS + " characters",
          new SourceLocation(token.getLine(), token.getCharPositionInLine() + 1),
          null,
          null,
          null);
    }
  }
}
