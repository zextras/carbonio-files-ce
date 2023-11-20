// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestingIT {

  private Simulator simulator;
  private NodeRepository nodeRepository;
  private LinkRepository linkRepository;
  private FileVersionRepository fileVersionRepository;

  @BeforeEach
  void setup() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", "00000000-0000-0000-0000-000000000000"))
            .withStorages()
            .withServer()
            .build()
            .start();

    Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
  }

  @AfterEach
  void clean() {
    simulator.stopAll();
  }

  void t() {

    EmbeddedChannel c = simulator.getNettyChannel();
    FullHttpRequest httpRequest =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/health/live");
    httpRequest.retain(1);
    c.writeInbound(httpRequest);

    FullHttpResponse response = c.readOutbound();
    Assertions.assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
  }

  @Test
  void getBlob() {
    // Given
    Node newNode =
        nodeRepository.createNewNode(
            UUID.randomUUID().toString(),
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-000000000000",
            "LOCAL_ROOT",
            "file1.txt",
            "",
            NodeType.TEXT,
            "LOCAL_ROOT",
            10L);
    FileVersion fv =
        fileVersionRepository
            .createNewFileVersion(
                newNode.getId(), "00000000-0000-0000-0000-000000000000", 1, "", 0L, "", false)
            .get();

    simulator.getBlob(newNode.getId(), 1);

    String queryGetLink =
        "query { getLinks(node_id: \\\""
            + newNode.getId()
            + "\\\") { id url expires_at created_at description}}";

    HttpResponse httpResponse =
        TestUtils.sendRequest(
            HttpRequest.of("GET", "/download/" + newNode.getId(), "ZM_AUTH_TOKEN=fake-token", null),
            simulator.getNettyChannel());

    System.out.println(httpResponse.getStatus());
    // System.out.println(TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getLink"));
    System.out.println(httpResponse.getBodyPayload());
  }

  @Test
  void publicFindNodes() {
    // Given
    Node newNode =
        nodeRepository.createNewNode(
            UUID.randomUUID().toString(),
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-000000000000",
            "LOCAL_ROOT",
            "file1.txt",
            "",
            NodeType.TEXT,
            "LOCAL_ROOT",
            10L);
    Link ciao =
        linkRepository.createLink(
            UUID.randomUUID().toString(),
            newNode.getId(),
            "1234abcd",
            Optional.empty(),
            Optional.of("ciao"));

    String queryGetLink =
        "query { getLinks(node_id: \\\""
            + newNode.getId()
            + "\\\") { id url expires_at created_at description}}";

    HttpResponse httpResponse =
        TestUtils.sendRequest(
            HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", queryGetLink),
            simulator.getNettyChannel());

    System.out.println(httpResponse.getStatus());
    // System.out.println(TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getLink"));
    System.out.println(httpResponse.getBodyPayload());
    /*
    nodeRepository.createNewNode(
        UUID.randomUUID().toString(),
        "owner-id",
        "owner-id",
        "LOCAL_ROOT",
        "file1.txt",
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        10L);

    nodeRepository.createNewNode(
        UUID.randomUUID().toString(),
        "owner-id",
        "owner-id",
        "LOCAL_ROOT",
        "file0.png",
        "",
        NodeType.IMAGE,
        "LOCAL_ROOT",
        50L);

    nodeRepository.createNewNode(
        UUID.randomUUID().toString(),
        "owner-id",
        "owner-id",
        "LOCAL_ROOT",
        "folder",
        "",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);



    String queryFindNodes =
        "query { findNodes(folder_id: \\\"LOCAL_ROOT\\\", limit: 2) { nodes {name}, page_token}}";

    HttpResponse httpResponse = TestUtils.sendRequest(HttpRequest.of("/public/graphql", "GET", queryFindNodes), simulator.getNettyChannel());
    String payload = TestUtils.queryPayload(queryFindNodes);
    ByteBuf content = Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8));

    FullHttpRequest httpRequest =
        new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/public/graphql/", content);
    httpRequest.retain(1);

    // When
    EmbeddedChannel channel = simulator.getNettyChannel();
    channel.writeInbound(httpRequest);
    DefaultFullHttpResponse response = channel.readOutbound();

    System.out.println(httpResponse.getStatus());
    System.out.println(TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getLink"));
     */
  }
}
