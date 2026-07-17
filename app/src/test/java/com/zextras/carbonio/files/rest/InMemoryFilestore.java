// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.filestore.model.Identifier;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link Filestore} fake for the blob integration tests. Annotated {@code @io.quarkus.test.Mock}
 * so it becomes a higher-priority CDI {@code @Alternative} of type {@link Filestore}, replacing the
 * production {@link com.zextras.carbonio.files.config.FilestoreProducer} bean under {@code @QuarkusTest}
 * — no real carbonio-storages container or WireMock stub is needed (Filestore is a plain interface).
 *
 * <p>Blobs are keyed by {@code node/version} (the account id is intentionally ignored, mirroring how
 * real storages resolves a blob by node id + version regardless of the requester account). Uploads
 * read the whole stream into memory; downloads replay it; bulk-delete removes it and always reports
 * success.
 */
@Mock
@ApplicationScoped
public class InMemoryFilestore implements Filestore {

  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

  /**
   * Test controls for {@link #bulkDelete}, used by {@code PurgeServiceIT} to exercise the
   * partial-failure / outage paths (this fake is otherwise always-succeeds, e.g. for
   * BlobResourceIT). Node ids are ignored by every other operation.
   */
  private final Set<String> failingBulkDeleteNodeIds = ConcurrentHashMap.newKeySet();

  private volatile boolean throwOnNextBulkDelete = false;

  // ---------------------------------------------------------------------------------------------
  // Acceptance-seam controls (P5c). The Guice+Netty acceptance suite drove a WireMock carbonio-
  // storages via StoragesMockHelper; on Quarkus, storages is this in-memory Filestore bean, so the
  // seam's Mocks#storages* calls flip these switches instead. All default to "happy path" so the
  // existing @QuarkusTest ITs are unaffected.
  // ---------------------------------------------------------------------------------------------

  /** Every bulkDelete throws (persistent outage), vs the one-shot {@link #throwOnNextBulkDelete}. */
  private volatile boolean bulkDeleteAlwaysThrows = false;

  /**
   * Every bulkDelete returns {@code null}, mirroring the real storages-ce-sdk {@code
   * StoragesClientImp#bulkDelete}, which reads {@code StoragesBulkDeleteResponse#getIds()} and
   * calls {@code .stream()} on it with no null-check: when PowerStore answers the bulk-delete
   * endpoint with a bodiless/idsless {@code {}} JSON payload, Gson leaves {@code ids == null} and
   * that call throws an uncaught {@code NullPointerException} out of {@code bulkDelete(...)}.
   * Product code (see {@code PurgeService}/{@code NodeDataFetcher}) treats ANY exception during
   * bulkDelete AND a literal {@code null} return identically (outage: keep tombstone, don't
   * increment attempts), so returning {@code null} here reproduces the same observable behavior
   * as the real NPE without needing to fabricate the SDK's internal stack trace.
   */
  private volatile boolean bulkDeleteReturnsNull = false;

  /** Upload (POST/PUT) throws, simulating a storages upload failure. */
  private volatile boolean uploadFails = false;

  /**
   * Upload returns a syntactically-valid response but stores NOTHING, so the post-upload existence
   * check (a re-download) reports the blob missing — mirrors StoragesMockHelper#verifyMissing().
   */
  private volatile boolean uploadSkipsStore = false;

  /** download() throws, simulating a dropped storages connection. */
  private volatile boolean downloadFails = false;

  /** copy() throws, simulating a storages copy failure. */
  private volatile boolean copyFails = false;

  private volatile Liveness liveness = Liveness.OK;

  /** Records every download identifier, so the seam can verify download hits/misses. */
  private final List<String> downloadLog = java.util.Collections.synchronizedList(new ArrayList<>());

  /** Test control: node ids in {@code nodeIds} are reported as FAILED by the next bulkDelete(s). */
  public void failBulkDeleteFor(String... nodeIds) {
    failingBulkDeleteNodeIds.addAll(List.of(nodeIds));
  }

  /** Test control: the very next bulkDelete() call throws, simulating a PowerStore outage. */
  public void throwOnNextBulkDelete() {
    throwOnNextBulkDelete = true;
  }

  /** Test control: clears any configured failure/outage simulation. Call in {@code @AfterEach}. */
  public void resetBulkDeleteFailures() {
    failingBulkDeleteNodeIds.clear();
    throwOnNextBulkDelete = false;
  }

  // -- acceptance-seam setters/inspectors --------------------------------------------------------

  public void setBulkDeleteAlwaysThrows(boolean value) {
    this.bulkDeleteAlwaysThrows = value;
  }

  public void setBulkDeleteReturnsNull(boolean value) {
    this.bulkDeleteReturnsNull = value;
  }

  public void setUploadFails(boolean value) {
    this.uploadFails = value;
  }

  public void setUploadSkipsStore(boolean value) {
    this.uploadSkipsStore = value;
  }

  public void setDownloadFails(boolean value) {
    this.downloadFails = value;
  }

  public void setCopyFails(boolean value) {
    this.copyFails = value;
  }

  public void setLiveness(Liveness value) {
    this.liveness = value;
  }

  /** Seeds deterministic bytes for {@code node/version}, so a later download serves them. */
  public void seedBlob(String node, int version, byte[] bytes) {
    blobs.put(key(node, version), bytes);
  }

  public boolean wasDownloaded(String node, int version) {
    return downloadLog.contains(key(node, version));
  }

  public int downloadCount() {
    return downloadLog.size();
  }

  /** Wipes all stored blobs. Used on acceptance app close() for cross-test-class hygiene. */
  public void clearAllBlobs() {
    blobs.clear();
  }

  /** Resets ALL acceptance-seam controls (and the download log) to the happy-path baseline. */
  public void resetAcceptanceControls() {
    resetBulkDeleteFailures();
    bulkDeleteAlwaysThrows = false;
    bulkDeleteReturnsNull = false;
    uploadFails = false;
    uploadSkipsStore = false;
    downloadFails = false;
    copyFails = false;
    liveness = Liveness.OK;
    downloadLog.clear();
  }

  private static String key(String node, int version) {
    return node + "/" + version;
  }

  private static String key(Identifier identifier) {
    FilesIdentifier files = (FilesIdentifier) identifier;
    return key(files.getNode(), files.getVersion());
  }

  /** Test helper: {@code true} if a blob for the given node/version has been stored. */
  public boolean has(String node, int version) {
    return blobs.containsKey(key(node, version));
  }

  @Override
  public void delete(Identifier identifier) {
    blobs.remove(key(identifier));
  }

  @Override
  public InputStream download(Identifier identifier) throws Exception {
    downloadLog.add(key(identifier));
    if (downloadFails) {
      throw new IllegalStateException("Simulated storages download connection drop");
    }
    byte[] bytes = blobs.get(key(identifier));
    if (bytes == null) {
      throw new IllegalStateException("Blob not found: " + key(identifier));
    }
    return new ByteArrayInputStream(bytes);
  }

  @Override
  public UploadResponse uploadPut(Identifier identifier, InputStream content, long size)
      throws Exception {
    return store(identifier, content);
  }

  @Override
  public UploadResponse uploadPost(Identifier identifier, InputStream content, long size)
      throws Exception {
    return store(identifier, content);
  }

  @Override
  public UploadResponse copy(Identifier source, Identifier destination, boolean overwrite)
      throws Exception {
    if (copyFails) {
      throw new RuntimeException("Simulated storages copy failure");
    }
    byte[] bytes = blobs.get(key(source));
    if (bytes == null) {
      throw new IllegalStateException("Blob not found: " + key(source));
    }
    blobs.put(key(destination), bytes);
    return uploadResponse(bytes.length);
  }

  @Override
  public List<BulkDeleteResponseItem> bulkDelete(
      IdentifierType type, String accountId, List<BulkDeleteRequestItem> items) {
    if (bulkDeleteAlwaysThrows) {
      throw new RuntimeException("Simulated PowerStore outage");
    }
    if (throwOnNextBulkDelete) {
      throwOnNextBulkDelete = false;
      throw new RuntimeException("Simulated PowerStore outage");
    }
    if (bulkDeleteReturnsNull) {
      return null;
    }
    List<BulkDeleteResponseItem> failed = new ArrayList<>();
    for (BulkDeleteRequestItem item : items) {
      if (failingBulkDeleteNodeIds.contains(item.getNode())) {
        failed.add(failedResponseItem(item.getNode()));
      } else {
        item.getVersion().ifPresent(version -> blobs.remove(key(item.getNode(), version)));
      }
    }
    return failed;
  }

  private static BulkDeleteResponseItem failedResponseItem(String nodeId) {
    return new BulkDeleteResponseItem() {
      @Override
      public IdentifierType getType() {
        return IdentifierType.files;
      }

      @Override
      public String getNode() {
        return nodeId;
      }

      @Override
      public Optional<Integer> getVersion() {
        return Optional.empty();
      }
    };
  }

  @Override
  public Liveness checkLiveness() {
    return liveness;
  }

  private UploadResponse store(Identifier identifier, InputStream content) throws Exception {
    if (uploadFails) {
      throw new RuntimeException("Simulated storages upload failure");
    }
    byte[] bytes = content.readAllBytes();
    if (!uploadSkipsStore) {
      blobs.put(key(identifier), bytes);
    }
    return uploadResponse(bytes.length);
  }

  private static UploadResponse uploadResponse(long size) {
    return new UploadResponse() {
      @Override
      public String getDigest() {
        return "test-digest-" + size;
      }

      @Override
      public long getSize() {
        return size;
      }

      @Override
      public String getDigestAlgorithm() {
        return "TEST";
      }
    };
  }
}
