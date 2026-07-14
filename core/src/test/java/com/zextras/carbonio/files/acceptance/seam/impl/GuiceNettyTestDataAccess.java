// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.acceptance.seam.TestDataAccess;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSQLCondition;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SQLExpression;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SortOrder;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;

import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

/**
 * {@link TestDataAccess} implementation resolving repositories from the {@link Simulator}'s Guice
 * {@link Injector} — the ONE sanctioned {@code injector.getInstance(...)} call site outside
 * {@link Simulator} itself. Never expose {@link Injector} or repository types through the {@link
 * TestDataAccess} interface.
 *
 * <p>Constructs {@link DatabasePopulator} via its existing {@code Injector} constructor for now;
 * a later migration (Phase-1 Task 2) changes {@link DatabasePopulator} to take repositories
 * directly, at which point only this class changes.
 */
class GuiceNettyTestDataAccess implements TestDataAccess {

  private final Simulator simulator;
  private final NodeRepository nodeRepository;
  private final TombstoneRepository tombstoneRepository;
  private final ShareRepository shareRepository;
  private final FileVersionRepository fileVersionRepository;

  GuiceNettyTestDataAccess(Simulator simulator) {
    this.simulator = simulator;
    Injector injector = simulator.getInjector();
    this.nodeRepository = injector.getInstance(NodeRepository.class);
    this.tombstoneRepository = injector.getInstance(TombstoneRepository.class);
    this.shareRepository = injector.getInstance(ShareRepository.class);
    this.fileVersionRepository = injector.getInstance(FileVersionRepository.class);
  }

  @Override
  public DatabasePopulator populator() {
    return DatabasePopulator.aNodePopulator(simulator.getInjector());
  }

  @Override
  public void resetDatabase() {
    simulator.resetDatabase();
  }

  @Override
  public boolean nodeExists(String nodeId) {
    return nodeRepository.getNode(nodeId).isPresent();
  }

  @Override
  public int tombstoneCount() {
    return tombstoneRepository.getTombstones().size();
  }

  @Override
  public int tombstoneCountForNode(String nodeId) {
    return (int)
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(nodeId))
            .count();
  }

  @Override
  public void clearTombstones() {
    tombstoneRepository
        .getTombstones()
        .forEach(
            t ->
                tombstoneRepository.deleteTombstonesByNodeAndVersion(t.getNodeId(), t.getVersion()));
  }

  @Override
  public void clearFileVersionCache() {
    simulator.clearFileVersionCache();
  }

  @Override
  public boolean shareExists(String nodeId, String userId) {
    return shareRepository.getShare(nodeId, userId).isPresent();
  }

  @Override
  public List<Integer> remainingVersionNumbers(String nodeId) {
    return fileVersionRepository
        .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_ASC))
        .stream()
        .map(fv -> fv.getVersion())
        .collect(Collectors.toList());
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

  /**
   * Builds the raw (pre-base64) JSON body of a tampered {@code findNodes} page-token. The
   * keySet/folderId/sort/limit values are fixture data (arbitrary but stable) built from Ebean-era
   * DAL internals ({@link NodeSQLCondition}/{@link SQLExpression}/{@link SortOrder}) — confined to
   * this impl class so no test body needs to import them. When {@code signature} is {@code null}
   * the JSON has no "signature" field at all (missing-signature case); otherwise it is prepended
   * as an explicit, deliberately-incorrect field (wrong-signature case).
   */
  private static String buildTamperedPageTokenJson(String signature) {
    SQLExpression keySet =
        SQLExpression.or(
            List.of(
                new NodeSQLCondition("node_category", SortOrder.ASCENDING, 1),
                SQLExpression.and(
                    List.of(
                        new NodeSQLCondition("node_category", SortOrder.EQUAL, 1),
                        new NodeSQLCondition("name", SortOrder.ASCENDING, "folder child"))),
                SQLExpression.and(
                    List.of(
                        new NodeSQLCondition("node_category", SortOrder.EQUAL, 1),
                        new NodeSQLCondition("name", SortOrder.EQUAL, "folder child"),
                        new NodeSQLCondition(
                            "node_id",
                            SortOrder.ASCENDING,
                            "88888888-8888-8888-8888-888888888888")))));

    final String jsonKeySet;
    try {
      jsonKeySet = new ObjectMapper().writeValueAsString(keySet);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize tampered page-token keySet", e);
    }

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
