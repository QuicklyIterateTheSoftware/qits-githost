package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.artifacts.control.BlobStore;
import eu.wohlben.qits.artifacts.control.ScratchBlob;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.githost.storage.PackBlobStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@link PackBlobStore} over the platform's own {@code BlobStore} — pack files, pack indexes and
 * reftables become ordinary content-addressed blobs, beside the OCI layers and the npm tarballs.
 *
 * <p>One of the two adapters that can only live in this module: {@code git-storage} declares the
 * port and may not depend on {@code artifacts}, {@code artifacts} may not depend on {@code
 * git-storage}, and this is the one module that already depends on both.
 *
 * <p><b>{@code BlobStore.IncrementalStage} cannot be used here, and that is the whole shape of this
 * class.</b> It is write-only — an {@code OutputStream} over a running digest — while JGit's pack
 * parser reads back deltas from a pack it has not finished storing. So a staged blob is a {@code
 * ScratchBlob} ({@code BlobStore.stageScratch}), the store's read-while-writing staging area:
 * chunks land in {@code blob_chunk} rows under a {@code STAGING} content id as they fill, a read
 * below the flushed watermark comes back from those rows and one above it from the single buffered
 * chunk in memory. There is no file anywhere on this path — the store has no filesystem backend at
 * all.
 *
 * <p>There is no delete, here or anywhere below. See {@link GitPack} for the number that makes that
 * the recorded posture rather than an omission.
 */
@ApplicationScoped
public class BlobStorePackBlobStore implements PackBlobStore {

  @Inject BlobStore blobs;

  /**
   * A random-access channel over the blob's chunk rows — {@code BlobStore.openChannel}, which is
   * exactly the seek-and-read JGit needs to serve a clone out of one large pack.
   */
  @Override
  public SeekableByteChannel locate(String blobId) throws IOException {
    try {
      return blobs.openChannel(blobId);
    } catch (NotFoundException e) {
      // The store answers a miss with its own 404-carrying exception; the port answers it with the
      // JDK's, because nothing across the port may be an artifacts type. JGit turns this into "the
      // pack is gone", which is a different situation from a broken read.
      throw new FileNotFoundException("no such git pack blob: " + blobId);
    }
  }

  @Override
  public StagedBlob stage() throws IOException {
    return new StagedChunksBlob(blobs.stageScratch());
  }

  /**
   * A blob being written into a scratch staging area, hashed at {@link #promote}.
   *
   * <p>Hashing happens on the finished content rather than incrementally while writing,
   * deliberately: an incremental digest is only correct if every byte arrives in order and exactly
   * once, and a wrong content address does not fail — it stores the bytes under a name nothing will
   * ever ask for. One pass over content this size is not worth that risk.
   */
  private final class StagedChunksBlob implements StagedBlob {

    private final ScratchBlob scratch;
    private boolean closed;

    StagedChunksBlob(ScratchBlob scratch) {
      this.scratch = scratch;
    }

    /** Appends at the current end, so {@link #read} can never disturb where the next one lands. */
    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
      scratch.write(buf, off, len);
    }

    @Override
    public int read(long position, ByteBuffer dst) throws IOException {
      return scratch.read(position, dst);
    }

    @Override
    public String promote() throws IOException {
      // openRead() SEALS the scratch: it writes the final short chunk, and a write after it throws.
      // So it has to come before promote and nothing may be written after — which is the natural
      // order anyway, because the address promote needs is what reading the content back computes.
      String sha256;
      try (InputStream content = scratch.openRead()) {
        sha256 = sha256(content);
      }
      // Storing bytes that are already stored is not an error: promote() drops the redundant
      // staging content and the address is the same, which is the dedupe the whole store is built
      // on.
      blobs.promote(new BlobStore.StagedBlob(sha256, scratch.size(), scratch.contentId()));
      return sha256;
    }

    /**
     * Called exactly once, always, including straight after {@link #promote} — where the staging
     * content has already been adopted or deduped away, so this must be a no-op rather than a
     * failure. The store's own close is guarded on the {@code STAGING} state, which is what makes
     * that true without a flag here. Getting it wrong does not look like a close error: JGit closes
     * a pack stream more than once and the symptom is {@code UnpackException} on every push.
     */
    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      scratch.close();
    }
  }

  private static String sha256(InputStream content) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 unavailable", e);
    }
    byte[] buf = new byte[64 * 1024];
    int n;
    while ((n = content.read(buf)) != -1) {
      digest.update(buf, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
