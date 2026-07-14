// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.acceptance.seam.TestDataAccess;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;

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

  GuiceNettyTestDataAccess(Simulator simulator) {
    this.simulator = simulator;
    Injector injector = simulator.getInjector();
    this.nodeRepository = injector.getInstance(NodeRepository.class);
    this.tombstoneRepository = injector.getInstance(TombstoneRepository.class);
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
  public void clearTombstones() {
    tombstoneRepository
        .getTombstones()
        .forEach(
            t ->
                tombstoneRepository.deleteTombstonesByNodeAndVersion(t.getNodeId(), t.getVersion()));
  }
}
