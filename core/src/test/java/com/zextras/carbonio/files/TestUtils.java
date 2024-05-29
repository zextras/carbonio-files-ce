// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
          return (Map<String, Object>) data.get(operation);
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

  public static Optional<String> jsonResponseToString(String json, String operation) {
    try {
      final Map<String, Object> result = new ObjectMapper().readValue(json, Map.class);

      if (result.get("data") != null) {
        final Map<String, Object> data = (Map<String, Object>) result.get("data");

        return Optional.ofNullable((String) data.get(operation));
      }
      return Optional.empty();

    } catch (JsonProcessingException exception) {
      return Optional.empty();
    }
  }

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

  public static HttpResponse sendRequest(HttpRequest request, EmbeddedChannel nettyChannel) {

    final ByteBuf payloadBuffer =
        Unpooled.wrappedBuffer(
            queryPayload(request.getBodyPayload().orElse("")).getBytes(StandardCharsets.UTF_8));

    DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();

    if (request.getCookie().isPresent()) {
      httpHeaders.add(HttpHeaderNames.COOKIE, request.getCookie().get());
    }

    final FullHttpRequest fullHttpRequest =
        new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(request.getMethod()),
            request.getEndpoint(),
            payloadBuffer,
            httpHeaders,
            httpHeaders);

    fullHttpRequest.retain(2);
    nettyChannel.writeInbound(fullHttpRequest);

    final DefaultHttpResponse defaultHttpResponse = nettyChannel.readOutbound();

    if (defaultHttpResponse instanceof DefaultFullHttpResponse fullHttpResponse) {
      return HttpResponse.of(
          fullHttpResponse.status().code(),
          fullHttpResponse.headers().entries(),
          fullHttpResponse.content().toString(StandardCharsets.UTF_8));
    }

    return HttpResponse.of(defaultHttpResponse.status().code(), defaultHttpResponse.headers().entries(), null);
  }
}
