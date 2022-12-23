package com.zextras.carbonio.files.utilities;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGenerator.Standard;

/**
 * @link https://junit.org/junit5/docs/current/user-guide/#writing-tests-display-name-generator
 */
public class MethodNameTransformer extends Standard {

  /**
   * Transforms a string from CamelCase format into a sentence where words are separated by spaces
   * and every character is lower case.
   *
   * @param name is a {@link String} to transform.
   *
   * @return a {@link String} transformed in a readable sentence.
   */
  private String transformCamelCaseToSentence(String name) {
    Matcher matcher = Pattern.compile("(([A-Z]?[a-z]+)|([A-Z]))").matcher(name);

    List<String> words = new ArrayList<>();
    while (matcher.find()) {
      words.add(matcher.group(0).toLowerCase());
    }

    return String.join(" ", words);
  }

  @Override
  public String generateDisplayNameForMethod(
    Class<?> aClass,
    Method method
  ) {
    return transformCamelCaseToSentence(super.generateDisplayNameForMethod(aClass, method));
  }
}
