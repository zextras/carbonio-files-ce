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
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
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

    if (request.getHeaders().isPresent()) {
      request.getHeaders().get().forEach(header -> httpHeaders.add(header.getKey(), header.getValue()));
    }

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

  public static HttpResponse sendFormRequest(HttpRequest request, EmbeddedChannel nettyChannel) {
    final ByteBuf payloadBuffer = Unpooled.wrappedBuffer(
        request.getBodyPayload().orElse("").getBytes(StandardCharsets.UTF_8)
    );

    DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();

    if (request.getHeaders().isPresent()) {
      request.getHeaders().get().forEach(header -> httpHeaders.add(header.getKey(), header.getValue()));
    }

    if (request.getCookie().isPresent()) {
      httpHeaders.add(HttpHeaderNames.COOKIE, request.getCookie().get());
    }

    final FullHttpRequest fullHttpRequest = new DefaultFullHttpRequest(
        HttpVersion.HTTP_1_1,
        HttpMethod.valueOf(request.getMethod()),
        request.getEndpoint(),
        payloadBuffer,
        httpHeaders,
        httpHeaders
    );

    fullHttpRequest.retain(2);
    nettyChannel.writeInbound(fullHttpRequest);

    // For async handlers (e.g. download-multiple with writePipedStream), the response
    // may not be immediately available. Run scheduled tasks to allow pollAndWrite to execute.
    DefaultHttpResponse defaultHttpResponse = nettyChannel.readOutbound();
    if (defaultHttpResponse == null) {
      long deadline = System.currentTimeMillis() + 5000;
      while (defaultHttpResponse == null && System.currentTimeMillis() < deadline) {
        try { Thread.sleep(20); } catch (InterruptedException ignored) { break; }
        nettyChannel.runScheduledPendingTasks();
        defaultHttpResponse = nettyChannel.readOutbound();
      }
    }

    if (defaultHttpResponse instanceof DefaultFullHttpResponse fullHttpResponse) {
      return HttpResponse.of(
          fullHttpResponse.status().code(),
          fullHttpResponse.headers().entries(),
          fullHttpResponse.content().toString(StandardCharsets.UTF_8)
      );
    }

    return HttpResponse.of(
        defaultHttpResponse.status().code(),
        defaultHttpResponse.headers().entries(),
        null
    );
  }

  /**
   * Drives a binary/streamed upload (routes {@code /upload}, {@code /upload-version},
   * {@code /internal/upload}, {@code /upload-to}) through the embedded Netty pipeline.
   *
   * <p>Unlike {@link #sendRequest}/{@link #sendFormRequest} (which write ONE {@code
   * DefaultFullHttpRequest} carrying the whole body), {@code BlobController}'s upload routes have
   * no {@code HttpObjectAggregator} in front of them (see {@code HttpRoutingHandler}) and read the
   * body chunk-by-chunk into a {@code BufferInputStream}. So this writes a headers-only {@code
   * DefaultHttpRequest} (the caller's headers/cookie plus a Content-Length computed from the
   * actual byte array — never taken from the caller's headers) followed by a single {@code
   * DefaultLastHttpContent} carrying the bytes.
   *
   * <p>The upload itself completes on a {@code CompletableFuture} (a background thread reads the
   * just-written content while it drives the Storages client), so — exactly like {@link
   * #sendFormRequest}'s async branch — the response may not be immediately available on {@code
   * readOutbound()}; poll with a deadline, pumping {@code runScheduledPendingTasks()} so the
   * background completion's {@code context.write(...)} (issued off the event-loop thread) gets
   * drained.
   */
  public static HttpResponse sendUpload(HttpRequest request, EmbeddedChannel nettyChannel) {
    final byte[] body = request.getBinaryBody().orElse(new byte[0]);

    DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();

    if (request.getHeaders().isPresent()) {
      request.getHeaders().get().forEach(header -> httpHeaders.add(header.getKey(), header.getValue()));
    }

    if (request.getCookie().isPresent()) {
      httpHeaders.add(HttpHeaderNames.COOKIE, request.getCookie().get());
    }

    // Always the real byte-array length: BlobController parses this to size the read loop and to
    // enforce the upload size cap, so it must reflect what is actually written below.
    httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length));

    final DefaultHttpRequest httpRequestHead =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(request.getMethod()),
            request.getEndpoint(),
            httpHeaders);

    nettyChannel.writeInbound(httpRequestHead);
    nettyChannel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(body)));

    DefaultHttpResponse defaultHttpResponse = nettyChannel.readOutbound();
    if (defaultHttpResponse == null) {
      long deadline = System.currentTimeMillis() + 5000;
      while (defaultHttpResponse == null && System.currentTimeMillis() < deadline) {
        try { Thread.sleep(20); } catch (InterruptedException ignored) { break; }
        nettyChannel.runScheduledPendingTasks();
        defaultHttpResponse = nettyChannel.readOutbound();
      }
    }

    if (defaultHttpResponse instanceof DefaultFullHttpResponse fullHttpResponse) {
      return HttpResponse.of(
          fullHttpResponse.status().code(),
          fullHttpResponse.headers().entries(),
          fullHttpResponse.content().toString(StandardCharsets.UTF_8));
    }

    return HttpResponse.of(
        defaultHttpResponse.status().code(),
        defaultHttpResponse.headers().entries(),
        null
    );
  }
}
