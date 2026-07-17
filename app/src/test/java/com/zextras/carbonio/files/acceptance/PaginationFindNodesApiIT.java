// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/*
 * This test class mimics findNodes tests but only checks if the pagination is correct and has been created
 * to avoid checking two things in the same test class, so findNodesApi will only check if the search returns correct
 * values without pagination and PaginationFindNodes will only check if those values are paginated correctly.
 * Obviously if the search is broken so will be the pagination since paginated values are returned based on the
 * search criteria.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PaginationFindNodesApiIT {

  static FilesTestApp app;

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement( // create a fake token to use in cookie for auth
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-account-for-sharing",
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            .build();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class PaginateByNameTests {

    @BeforeAll
    void setUp() {
      app.backdoor().populator()
          .addNode(
              new SimplePopulatorFolder(
                  "10000000-0000-0000-0000-000000000001",
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "folderA"))
          .addNode(
              new SimplePopulatorFolder(
                  "10000000-0000-0000-0000-000000000002",
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "folderB"))
          .addNode(
              new SimplePopulatorTextFile(
                  "00000000-0000-0000-0000-000000000001",
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "aaa.txt"))
          .addNode(
              new SimplePopulatorTextFile(
                  "00000000-0000-0000-0000-000000000002",
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "bbb.txt"))
          .addNode(
              new SimplePopulatorTextFile(
                  "00000000-0000-0000-0000-000000000003",
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "ccc.txt"));
    }

    @AfterAll
    void tearDown() {
      app.backdoor().resetDatabase();
    }

    @Test
    void givenFilesOnRootSearchWithSortNameAscShouldReturnCorrectlyPaginatedNodes() {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "NAME_ASC")
              .withInteger("limit", 4)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequest =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
          TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "NAME_ASC")
              .withInteger("limit", 1)
              .withString("page_token", pageToken)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequestPage =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
          TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
          .containsEntry("id", "00000000-0000-0000-0000-000000000003")
          .containsEntry("name", "ccc");
    }

    @Test
    void givenFilesOnRootSearchWithSortNameDescShouldReturnCorrectlyPaginatedNodes() {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "NAME_DESC")
              .withInteger("limit", 4)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequest =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
          TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "NAME_DESC")
              .withInteger("limit", 1)
              .withString("page_token", pageToken)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequestPage =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
          TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
          .containsEntry("id", "00000000-0000-0000-0000-000000000001")
          .containsEntry("name", "aaa");
    }

    @Test
    void givenFilesOnRootSearchWithSortLastUpdateAscShouldReturnCorrectlyPaginatedNodes() {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "UPDATED_AT_ASC")
              .withInteger("limit", 4)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequest =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
          TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "UPDATED_AT_ASC")
              .withInteger("limit", 1)
              .withString("page_token", pageToken)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequestPage =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
          TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
          .containsEntry("id", "00000000-0000-0000-0000-000000000003")
          .containsEntry("name", "ccc");
    }

    @Test
    void
        givenFilesOnRootSearchWithSortLastUpdateDescShouldReturnCorrectlyPaginatedNodes() { // recents
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "UPDATED_AT_DESC")
              .withInteger("limit", 4)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequest =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
          TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "UPDATED_AT_DESC")
              .withInteger("limit", 1)
              .withString("page_token", pageToken)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequestPage =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
          TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
          .containsEntry("id", "00000000-0000-0000-0000-000000000001")
          .containsEntry("name", "aaa");
    }
  }

  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class PaginateBySizeTests {

    @BeforeAll
    void setUp() {
      app.backdoor().populator()
          .addNode(
              new SimplePopulatorFolder(
                  "10000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
          .addNode(
              new SimplePopulatorFolder(
                  "10000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
          .addNode(
              new SimplePopulatorTextFile(
                  "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 0L))
          .addNode(
              new SimplePopulatorTextFile(
                  "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1L))
          .addNode(
              new SimplePopulatorTextFile(
                  "00000000-0000-0000-0000-000000000003",
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  2L));
    }

    @AfterAll
    void tearDown() {
      app.backdoor().resetDatabase();
    }

    @Test
    void givenFilesOnRootSearchWithSortSizeAscShouldReturnCorrectlyPaginatedNodes() {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "SIZE_ASC")
              .withInteger("limit", 4)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequest =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
          TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "SIZE_ASC")
              .withInteger("limit", 1)
              .withString("page_token", pageToken)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequestPage =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
          TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
          .containsEntry("id", "00000000-0000-0000-0000-000000000003")
          .containsEntry("name", "fake");
    }

    @Test
    void givenFilesOnRootSearchWithSortSizeDescShouldReturnCorrectlyPaginatedNodes() {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "SIZE_DESC")
              .withInteger("limit", 4)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequest =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
          TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
          GraphqlCommandBuilder.aQueryBuilder("findNodes")
              .withString("folder_id", "LOCAL_ROOT")
              .withBoolean("cascade", true)
              .withEnumLiteral("sort", "SIZE_DESC")
              .withInteger("limit", 1)
              .withString("page_token", pageToken)
              .withWantedResultFormat("{ nodes { id name }, page_token }")
              .build();

      final HttpRequest httpRequestPage =
          HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
          TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
          .containsEntry("id", "10000000-0000-0000-0000-000000000002")
          .containsEntry("name", "folder");
    }
  }

  @Nested
  class SQLInjectionTests {

    @AfterEach
    void cleanUp() {
      app.backdoor().resetDatabase();
    }

    @Test
    @DisplayName("""
      Given a LOCAL_ROOT with three nodes inside, a limit of two elements per page and the second
      element is a file having a filename with a SQL injected: the findNodes should not be vulnerable
      by the injection and should return the second page containing the third node and a null
      page_token
      """)
    void givenFilesOnRootHavingInjectedSQLInFilenameSearchWithSortNameAscShouldNotBeVulnerable() {
      app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000001",
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "folderA"))
        .addNode(new SimplePopulatorTextFile(
          "00000000-0000-0000-0000-000000000001",
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "test\')) OR 1=1 --file.txt"))
        .addNode(new SimplePopulatorTextFile(
          "00000000-0000-0000-0000-000000000002",
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "y file.txt"))
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000002",
          "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          "g folder"))
        .addNode(new SimplePopulatorTextFile(
          "00000000-0000-0000-0000-000000000003",
          "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          "z file.txt"));

      String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
          .withString("folder_id", "LOCAL_ROOT")
          .withEnumLiteral("sort", "NAME_ASC")
          .withInteger("limit", 2)
          .withWantedResultFormat("{ nodes { id name }, page_token }")
          .build();

      final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
          .withString("folder_id", "LOCAL_ROOT")
          .withEnumLiteral("sort", "NAME_ASC")
          .withInteger("limit", 2)
          .withString("page_token", pageToken)
          .withWantedResultFormat("{ nodes { id name }, page_token }")
          .build();

      final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "y file");
    }

    @DisplayName("""
      Given a LOCAL_ROOT with three nodes inside, a limit of two elements per page and the second
      element is a folder having a filename with a SQL injected: the findNodes should not be
      vulnerable by the injection and should return the second page containing the third node and a
      null page_token
      """)
    @Test
    void givenFoldersOnRootHavingInjectedSQLInFilenameSearchWithSortNameAscShouldNotBeVulnerable() {
      app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000001",
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "folderA"))
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000002",
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "folderB\')) OR 1=1 --"))
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000003",
          "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "g folder"))
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000004",
          "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          "g folder"))
        .addNode(new SimplePopulatorFolder(
          "10000000-0000-0000-0000-000000000005",
          "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          "h folder"));

      String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
          .withString("folder_id", "LOCAL_ROOT")
          .withEnumLiteral("sort", "NAME_ASC")
          .withInteger("limit", 2)
          .withWantedResultFormat("{ nodes { id name }, page_token }")
          .build();

      final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

      final HttpResponse httpResponse = app.send(httpRequest);

      final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
      final String pageToken = (String) page.get("page_token");

      String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
          .withString("folder_id", "LOCAL_ROOT")
          .withEnumLiteral("sort", "NAME_ASC")
          .withInteger("limit", 2)
          .withString("page_token", pageToken)
          .withWantedResultFormat("{ nodes { id name }, page_token }")
          .build();

      final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

      final HttpResponse httpResponsePage = app.send(httpRequestPage);

      Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

      final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

      final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

      Assertions.assertThat(nodes).hasSize(1);
      Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000003")
        .containsEntry("name", "g folder");
    }
  }
}
