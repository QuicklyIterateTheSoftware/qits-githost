package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.artifacts.control.BlobStore;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.githost.storage.PackBlobStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * parser reads back deltas from a pack it has not finished storing. So a staged blob is this
 * adapter's own read-write file in the store's temp area ({@code BlobStore.newStagingFile}, which is
 * in that area precisely because {@code promote} finishes with an atomic move), hashed when JGit
 * closes it and handed to {@code BlobStore.promote} as a finished {@code StagedBlob}.
 *
 * <p>There is no delete, here or anywhere below. See {@link GitPack} for the number that makes that
 * the recorded posture rather than an omission.
 */
@ApplicationScoped
public class BlobStorePackBlobStore implements PackBlobStore {

  @Inject BlobStore blobs;

  /**
   * A {@code FileChannel} over the blob's own path — the zero-copy read {@code BlobStore.locate}
   * exists for, and exactly the random access JGit needs to serve a clone out of one large pack.
   */
  @Override
  public SeekableByteChannel locate(String blobId) throws IOException {
    Path path;
    try {
      path = blobs.locate(blobId);
    } catch (NotFoundException e) {
      // The store answers a miss with its own 404-carrying exception; the port answers it with the
      // JDK's, because nothing across the port may be an artifacts type. JGit turns this into "the
      // pack is gone", which is a different situation from a broken read.
      throw new FileNotFoundException("no such git pack blob: " + blobId);
    }
    return FileChannel.open(path, StandardOpenOption.READ);
  }

  @Override
  public StagedBlob stage() throws IOException {
    return new TempFileBlob(blobs.newStagingFile());
  }

  /**
   * A blob being written into a read-write temp file, hashed at {@link #promote}.
   *
   * <p>Hashing happens on the finished file rather than incrementally while writing, deliberately:
   * an incremental digest is only correct if every byte arrives in order and exactly once, and a
   * wrong content address does not fail — it stores the bytes under a name nothing will ever ask
   * for. One pass over a file this size is not worth that risk.
   */
  private final class TempFileBlob implements StagedBlob {

    private final Path path;
    private final FileChannel channel;
    private long end;
    private boolean promoted;
    private boolean closed;

    TempFileBlob(Path path) throws IOException {
      this.path = path;
      this.channel =
          FileChannel.open(
              path,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE);
    }

    /** Positional writes, so {@link #read} can never disturb where the next append lands. */
    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
      ByteBuffer src = ByteBuffer.wrap(buf, off, len);
      while (src.hasRemaining()) {
        end += channel.write(src, end);
      }
    }

    @Override
    public int read(long position, ByteBuffer dst) throws IOException {
      return channel.read(dst, position);
    }

    @Override
    public String promote() throws IOException {
      channel.force(true);
      channel.close();
      String sha256 = sha256(path);
      long size = Files.size(path);
      // Storing bytes that are already stored is not an error: promote() discards the temp file and
      // the address is the same, which is the dedupe the whole store is built on.
      blobs.promote(new BlobStore.StagedBlob(sha256, size, path));
      promoted = true;
      return sha256;
    }

    /**
     * Called exactly once, always, including straight after {@link #promote} — where the temp file
     * has already been moved away, so this must be a no-op rather than a failure. Getting that
     * wrong does not look like a close error: JGit closes a pack stream more than once and the
     * symptom is {@code UnpackException} on every push.
     */
    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      if (promoted) {
        return;
      }
      try {
        channel.close();
      } finally {
        Files.deleteIfExists(path);
      }
    }
  }

  private static String sha256(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 unavailable", e);
    }
    try (FileChannel in = FileChannel.open(path, StandardOpenOption.READ)) {
      ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
      while (in.read(buf) != -1) {
        buf.flip();
        digest.update(buf);
        buf.clear();
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
