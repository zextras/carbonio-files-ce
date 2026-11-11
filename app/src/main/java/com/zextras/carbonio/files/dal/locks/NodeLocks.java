// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import com.zextras.carbonio.files.Constants.Db.RootId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.Session;

@ApplicationScoped
public class NodeLocks {

  private static final String IDLE_IN_TRANSACTION_TIMEOUT = "60s";
  private static final Set<String> ROOTS = Set.of(RootId.LOCAL_ROOT, RootId.TRASH_ROOT);
  private static final Set<String> LOCK_WAIT_FAILURES = Set.of("55P03", "57014", "40P01");
  private static final String DEADLOCK = "40P01";
  private static final String SAVEPOINT = "files_node_locks";
  private static final String CHILDREN_KEY_PREFIX = "files-children:";
  private static final String DEPTH =
      "length(coalesce(ancestor_ids, '')) - length(replace(coalesce(ancestor_ids, ''), ',', ''))";
  private static final String LOCK_ORDER = DEPTH + ", node_id COLLATE \"C\"";

  private record Row(String id, int depth, boolean exclusive) {}

  private final EntityManager entityManager;
  private final Duration waitBudget;

  @Inject
  public NodeLocks(
      EntityManager entityManager,
      @ConfigProperty(name = "carbonio.files.node-locks.wait", defaultValue = "5s")
          Duration waitBudget) {
    this.entityManager = entityManager;
    this.waitBudget = waitBudget;
  }

  /**
   * First statement of a write transaction: the changed rows FOR NO KEY UPDATE, their ancestors FOR
   * SHARE. Entities loaded before are detached; read everything again afterwards.
   */
  @Transactional(TxType.MANDATORY)
  public Set<String> lockForChange(Collection<String> nodeIds) {
    return lock(nodeIds, List.of(), waitBudget);
  }

  @Transactional(TxType.MANDATORY)
  Set<String> lockForChange(Collection<String> nodeIds, Duration budget) {
    return lock(nodeIds, List.of(), budget);
  }

  /**
   * Like {@link #lockForChange}, plus parents receiving a child: FOR SHARE and an advisory lock.
   */
  @Transactional(TxType.MANDATORY)
  public Set<String> lock(Collection<String> changedIds, Collection<String> parentIds) {
    return lock(changedIds, parentIds, waitBudget);
  }

  @Transactional(TxType.MANDATORY)
  Set<String> lock(Collection<String> changedIds, Collection<String> parentIds, Duration budget) {
    Session session = entityManager.unwrap(Session.class);
    if (session.isDirty()) {
      throw new IllegalStateException("Node locks must be taken before any write");
    }
    Set<String> anchors = sanitize(changedIds);
    Set<String> parents = sanitize(parentIds);
    if (anchors.isEmpty() && parents.isEmpty()) {
      return Set.of();
    }
    Set<String> locked =
        session.doReturningWork(connection -> acquire(connection, anchors, parents, budget));
    entityManager.clear();
    return locked;
  }

  /**
   * First statement of a transaction that deletes every non-root row of the owner. FOR UPDATE
   * because the rows are then deleted: upgrading later could deadlock. Unbounded wait, no retry.
   */
  @Transactional(TxType.MANDATORY)
  public void lockOwnerRowsForDeletion(String ownerId) {
    lockOwnerRows(
        "SELECT 1 FROM node WHERE owner_id = ? AND node_type <> 'ROOT' ORDER BY "
            + LOCK_ORDER
            + " FOR UPDATE",
        statement -> statement.setString(1, ownerId));
  }

  /**
   * First statement of a transaction that flips the hidden flag of the owner's rows: locks exactly
   * the rows whose flag differs. Unbounded wait, no retry.
   */
  @Transactional(TxType.MANDATORY)
  public void lockOwnerRowsForHiddenChange(String ownerId, boolean hidden) {
    lockOwnerRows(
        "SELECT 1 FROM node WHERE owner_id = ? AND hidden <> ? ORDER BY "
            + LOCK_ORDER
            + " FOR NO KEY UPDATE",
        statement -> {
          statement.setString(1, ownerId);
          statement.setBoolean(2, hidden);
        });
  }

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private void lockOwnerRows(String lockQuery, Binder binder) {
    entityManager
        .unwrap(Session.class)
        .doWork(
            connection -> {
              String lockTimeout = setting(connection, "lock_timeout");
              String statementTimeout = setting(connection, "statement_timeout");
              setTimeouts(connection, "0", "0");
              try (PreparedStatement statement =
                  connection.prepareStatement("SELECT count(*) FROM (" + lockQuery + ") s")) {
                binder.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                  rows.next();
                  rows.getLong(1);
                }
              }
              setTimeouts(connection, lockTimeout, statementTimeout);
            });
  }

  private static Set<String> sanitize(Collection<String> ids) {
    Set<String> result = new LinkedHashSet<>();
    ids.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(id -> !id.isEmpty() && id.length() <= 36 && !ROOTS.contains(id))
        .forEach(result::add);
    return result;
  }

  private Set<String> acquire(
      Connection connection, Set<String> anchors, Set<String> parents, Duration budget)
      throws SQLException {
    Set<String> requested = new LinkedHashSet<>(anchors);
    requested.addAll(parents);
    if (!queryBoolean(connection, "SELECT pg_current_xact_id_if_assigned() IS NULL")) {
      throw new IllegalStateException("Node locks must be taken before any write");
    }
    String lockTimeout = setting(connection, "lock_timeout");
    String statementTimeout = setting(connection, "statement_timeout");
    long deadline = System.nanoTime() + budget.toNanos();
    execute(connection, "SAVEPOINT " + SAVEPOINT);
    while (true) {
      try {
        List<Row> plan = plan(connection, anchors, parents);
        Set<String> locked = new HashSet<>();
        for (List<Row> run : runs(plan)) {
          long remaining = remainingMillis(deadline);
          if (remaining <= 0) {
            giveUp(connection, requested, budget);
          }
          setTimeouts(connection, remaining + "ms", remaining + "ms");
          locked.addAll(lockRun(connection, run));
        }
        if (!parents.isEmpty()) {
          long remaining = remainingMillis(deadline);
          if (remaining <= 0) {
            giveUp(connection, requested, budget);
          }
          setTimeouts(connection, remaining + "ms", remaining + "ms");
          lockChildren(connection, parents);
        }
        setTimeouts(connection, lockTimeout, statementTimeout);
        Set<String> required = new HashSet<>();
        plan(connection, anchors, parents).forEach(row -> required.add(row.id()));
        if (locked.containsAll(required)) {
          setLocal(connection, "idle_in_transaction_session_timeout", IDLE_IN_TRANSACTION_TIMEOUT);
          execute(connection, "RELEASE SAVEPOINT " + SAVEPOINT);
          return Set.copyOf(locked);
        }
        execute(connection, "ROLLBACK TO SAVEPOINT " + SAVEPOINT);
      } catch (SQLException e) {
        if (!LOCK_WAIT_FAILURES.contains(e.getSQLState())) {
          throw e;
        }
        execute(connection, "ROLLBACK TO SAVEPOINT " + SAVEPOINT);
        if (!DEADLOCK.equals(e.getSQLState())) {
          throw new NodeLockTimeoutException(requested, budget, e);
        }
      }
      if (remainingMillis(deadline) <= 0) {
        throw new NodeLockTimeoutException(requested, budget);
      }
    }
  }

  private List<Row> plan(Connection connection, Set<String> anchors, Set<String> parents)
      throws SQLException {
    Map<String, String> anchorChains = chains(connection, anchors);
    Set<String> sharedParents = new LinkedHashSet<>(parents);
    sharedParents.removeAll(anchors);
    Map<String, String> parentChains = chains(connection, sharedParents);
    Set<String> ancestors = new LinkedHashSet<>();
    Stream.concat(anchorChains.values().stream(), parentChains.values().stream())
        .filter(Objects::nonNull)
        .flatMap(chain -> Arrays.stream(chain.split(",")))
        .map(String::trim)
        .filter(
            id ->
                !id.isEmpty()
                    && !ROOTS.contains(id)
                    && !anchors.contains(id)
                    && !sharedParents.contains(id))
        .forEach(ancestors::add);
    Map<String, String> ancestorChains = chains(connection, ancestors);
    List<Row> rows = new ArrayList<>();
    anchorChains.forEach((id, chain) -> rows.add(new Row(id, depth(chain), true)));
    parentChains.forEach((id, chain) -> rows.add(new Row(id, depth(chain), false)));
    ancestorChains.forEach((id, chain) -> rows.add(new Row(id, depth(chain), false)));
    rows.sort(Comparator.comparingInt(Row::depth).thenComparing(Row::id));
    return rows;
  }

  private static List<List<Row>> runs(List<Row> plan) {
    List<List<Row>> runs = new ArrayList<>();
    for (Row row : plan) {
      if (runs.isEmpty() || runs.getLast().getFirst().exclusive() != row.exclusive()) {
        runs.add(new ArrayList<>());
      }
      runs.getLast().add(row);
    }
    return runs;
  }

  private static Set<String> lockRun(Connection connection, List<Row> run) throws SQLException {
    String mode = run.getFirst().exclusive() ? "FOR NO KEY UPDATE" : "FOR SHARE";
    String sql =
        "SELECT trim(node_id) FROM node WHERE node_id = ANY(?::character(36)[]) ORDER BY "
            + LOCK_ORDER
            + " "
            + mode;
    Set<String> locked = new HashSet<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setArray(1, connection.createArrayOf("text", run.stream().map(Row::id).toArray()));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          locked.add(rows.getString(1));
        }
      }
    }
    return locked;
  }

  private static void giveUp(Connection connection, Set<String> requested, Duration budget)
      throws SQLException {
    execute(connection, "ROLLBACK TO SAVEPOINT " + SAVEPOINT);
    throw new NodeLockTimeoutException(requested, budget);
  }

  private static void lockChildren(Connection connection, Set<String> parents) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      for (String parent : parents.stream().sorted().toList()) {
        statement.setString(1, CHILDREN_KEY_PREFIX + parent);
        statement.execute();
      }
    }
  }

  private static Map<String, String> chains(Connection connection, Set<String> ids)
      throws SQLException {
    Map<String, String> chains = new HashMap<>();
    if (ids.isEmpty()) {
      return chains;
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT trim(node_id), ancestor_ids FROM node WHERE node_id ="
                + " ANY(?::character(36)[])")) {
      statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          chains.put(rows.getString(1), rows.getString(2));
        }
      }
    }
    return chains;
  }

  private static int depth(String chain) {
    return chain == null ? 0 : (int) chain.chars().filter(c -> c == ',').count();
  }

  private static long remainingMillis(long deadline) {
    return Duration.ofNanos(deadline - System.nanoTime()).toMillis();
  }

  private static String setting(Connection connection, String name) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT current_setting(?)")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }

  private static void setTimeouts(
      Connection connection, String lockTimeout, String statementTimeout) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT set_config('lock_timeout', ?, true), set_config('statement_timeout', ?,"
                + " true)")) {
      statement.setString(1, lockTimeout);
      statement.setString(2, statementTimeout);
      statement.execute();
    }
  }

  private static void setLocal(Connection connection, String name, String value)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config(?, ?, true)")) {
      statement.setString(1, name);
      statement.setString(2, value);
      statement.execute();
    }
  }

  private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
