// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.acceptance.seam.TestDataAccess;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.files.tasks.AcceptancePurgeBridge;
import com.zextras.carbonio.files.tasks.PurgeService;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * {@link TestDataAccess} implementation resolving Panache repositories/services from the Quarkus
 * Arc CDI container and driving the shared {@code @QuarkusTest} Postgres. The Quarkus counterpart
 * of the legacy {@code GuiceNettyTestDataAccess} (which resolved Ebean repos from the Guice
 * injector). Read methods run inside a fresh transaction because the app's repository read methods
 * are not themselves {@code @Transactional}.
 */
class QuarkusTestDataAccess implements TestDataAccess {

  private static <T> T bean(Class<T> type) {
    return Arc.container().instance(type).get();
  }

  private NodeRepository nodeRepository() {
    return bean(NodeRepository.class);
  }

  private FileVersionRepository fileVersionRepository() {
    return bean(FileVersionRepository.class);
  }

  private ShareRepository shareRepository() {
    return bean(ShareRepository.class);
  }

  private TombstoneRepository tombstoneRepository() {
    return bean(TombstoneRepository.class);
  }

  private LinkRepository linkRepository() {
    return bean(LinkRepository.class);
  }

  @Override
  public DatabasePopulator populator() {
    return new DatabasePopulator(
        nodeRepository(), fileVersionRepository(), linkRepository(), shareRepository());
  }

  @Override
  public void resetDatabase() {
    DataSource dataSource = bean(DataSource.class);
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      // Delete test nodes but preserve ROOT nodes (LOCAL_ROOT/TRASH_ROOT, null owner_id). FK
      // cascades wipe activity/custom/link/revision/share/trashed. Mirrors Simulator#resetDatabase.
      statement.execute("DELETE FROM node WHERE owner_id IS NOT NULL");
      // Notification + snapshot tables are not FK-linked to node, so cascade above misses them.
      statement.execute(
          "TRUNCATE user_notification_interest, notification, snapshot_node, snapshot_user,"
              + " user_notifications_info CASCADE");
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to reset the test database", e);
    }
  }

  @Override
  public boolean nodeExists(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> nodeRepository().getNode(nodeId).isPresent());
  }

  @Override
  public int tombstoneCount() {
    return QuarkusTransaction.requiringNew().call(() -> tombstoneRepository().getTombstones().size());
  }

  @Override
  public int tombstoneCountForNode(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                (int)
                    tombstoneRepository().getTombstones().stream()
                        .filter(t -> t.getNodeId().equals(nodeId))
                        .count());
  }

  @Override
  public void clearTombstones() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                tombstoneRepository()
                    .getTombstones()
                    .forEach(
                        t ->
                            tombstoneRepository()
                                .deleteTombstonesByNodeAndVersion(t.getNodeId(), t.getVersion())));
  }

  @Override
  public void clearFileVersionCache() {
    bean(CacheHandler.class).getFileVersionCache().flushAll();
  }

  @Override
  public boolean shareExists(String nodeId, String userId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> shareRepository().getShare(nodeId, userId).isPresent());
  }

  @Override
  public List<Integer> remainingVersionNumbers(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                fileVersionRepository()
                    .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_ASC))
                    .stream()
                    .map(fv -> fv.getVersion())
                    .collect(Collectors.toList()));
  }

  @Override
  public String forgeTamperedPageTokenMissingSignature() {
    return Base64.getEncoder()
        .encodeToString(buildTamperedPageTokenJson(null).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String forgeTamperedPageTokenWithWrongSignature(String wrongSignature) {
    return Base64.getEncoder()
        .encodeToString(
            buildTamperedPageTokenJson(wrongSignature).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void runPurge() {
    AcceptancePurgeBridge.run(bean(PurgeService.class));
  }

  @Override
  public void injectUserStatusChanged(String userId, String status) {
    new UserStatusChangedConsumer(nodeRepository())
        .doHandle(
            new UserStatusChanged(userId, UserStatus.valueOf(status.toUpperCase(Locale.ROOT))));
  }

  @Override
  public void injectMaxVersionNumberChanged(int newMax) {
    new KeyValueChangedConsumer(fileVersionRepository())
        .doHandle(
            new KeyValueChanged(
                "carbonio-files/max-number-of-versions", String.valueOf(newMax)));
  }

  /**
   * Builds the raw (pre-base64) JSON of a tampered {@code findNodes} page-token. The Ebean-era
   * {@code SQLExpression}/{@code NodeSQLCondition} helpers the legacy impl used to build the keySet
   * are gone on Quarkus, so the keySet is inlined as an equivalent literal JSON object. When {@code
   * signature} is {@code null} the JSON omits the {@code signature} field entirely (missing-signature
   * case); otherwise it is set to the given (deliberately-wrong) value.
   */
  private static String buildTamperedPageTokenJson(String signature) {
    String jsonKeySet =
        "{\"operator\":\"OR\",\"expressions\":["
            + "{\"column\":\"node_category\",\"order\":\"ASCENDING\",\"value\":1},"
            + "{\"operator\":\"AND\",\"expressions\":["
            + "{\"column\":\"node_category\",\"order\":\"EQUAL\",\"value\":1},"
            + "{\"column\":\"name\",\"order\":\"ASCENDING\",\"value\":\"folder child\"}]}]}";

    String signatureField = signature == null ? "" : "\"signature\": \"" + signature + "\",\n  ";

    return String.format(
        """
        {
          %s"limit": 1,
          "keywords": [],
          "keySet": %s,
          "sort": "NAME_ASC",
          "flagged": null,
          "folderId": "77777777-7777-7777-7777-777777777777",
          "cascade": null,
          "sharedWithMe": null,
          "sharedByMe": null,
          "directShare": null,
          "nodeType": null,
          "ownerId": null
        }""",
        signatureField, jsonKeySet);
  }
}
