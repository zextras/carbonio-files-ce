// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Trimmed, transport-neutral test utilities for the Quarkus acceptance suite. The legacy core
 * {@code TestUtils} also carried Netty {@code EmbeddedChannel} send helpers; those are gone here —
 * the {@code QuarkusFilesTestApp} seam drives the app over real HTTP (RestAssured port), so the
 * acceptance bodies only need the JSON-response and GraphQL-payload helpers below.
 */
public class TestUtils {

  public static String queryPayload(String query) {
    return String.format("{\"query\":\"%s\"}", query);
  }

  public static String mutationPayload(String mutation) {
    return String.format("{\"mutation\":\"%s\"}", mutation);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> jsonResponseToMap(String json, String operation) {
    try {
      final Map<String, Object> result = new ObjectMapper().readValue(json, HashMap.class);

      if (result.get("data") != null) {
        final Map<String, Object> data = (Map<String, Object>) result.get("data");

        if (data.get(operation) != null) {
          Object dataOperation = data.get(operation);
          if (dataOperation instanceof ArrayList<?>) {
            Map<String, Object> listResult = new HashMap<>();
            listResult.put("data", dataOperation);
            return listResult;
          }
          return (Map<String, Object>) dataOperation;
        }
      }
      return Collections.emptyMap();

    } catch (JsonProcessingException exception) {
      return Collections.emptyMap();
    }
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> jsonResponseToList(String json, String operation) {
    try {
      final Map<String, Object> result = new ObjectMapper().readValue(json, Map.class);

      if (result.get("data") != null) {
        final Map<String, Object> data = (Map<String, Object>) result.get("data");

        if (data.get(operation) != null) {
          return (List<Map<String, Object>>) data.get(operation);
        }
      }
      return Collections.emptyList();

    } catch (JsonProcessingException exception) {
      return Collections.emptyList();
    }
  }

  @SuppressWarnings("unchecked")
  public static Optional<Object> jsonResponseToValue(String json, String operation) {
    try {
      final Map<String, Object> result = new ObjectMapper().readValue(json, Map.class);

      if (result.get("data") != null) {
        final Map<String, Object> data = (Map<String, Object>) result.get("data");

        return Optional.ofNullable(data.get(operation));
      }
      return Optional.empty();

    } catch (JsonProcessingException exception) {
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  public static List<String> jsonResponseToErrors(String json) {
    try {
      final Map<String, Object> result = new ObjectMapper().readValue(json, Map.class);

      if (result.get("errors") != null) {
        final List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");

        return errors.stream()
            .map(error -> (String) error.get("message"))
            .collect(Collectors.toList());
      }
    } catch (JsonProcessingException exception) {
      return Collections.emptyList();
    }
    return Collections.emptyList();
  }
}
