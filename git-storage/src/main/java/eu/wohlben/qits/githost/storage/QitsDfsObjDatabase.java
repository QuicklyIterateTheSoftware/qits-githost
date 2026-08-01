package eu.wohlben.qits.githost.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsPackFile;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;

/**
 * The object database: JGit's six DFS methods, answered by a {@link PackBlobStore} and a {@link
 * PackCatalog}.
 *
 * <p>The whole class is that translation and nothing else. It holds no state of its own — no cache,
 * no open file, no counter — so two instances of it over the same catalog see the same repository,
 * and a restart loses nothing.
 */
public class QitsDfsObjDatabase extends DfsObjDatabase {

  private final String repositoryId;
  private final PackBlobStore blobs;
  private final PackCatalog catalog;

  QitsDfsObjDatabase(
      QitsDfsRepository repository,
      PackBlobStore blobs,
      PackCatalog catalog,
      DfsReaderOptions options) {
    super(repository, options);
    this.repositoryId = repository.getRepositoryId();
    this.blobs = blobs;
    this.catalog = catalog;
  }

  /**
   * Names a new pack.
   *
   * <p>The name is a UUID rather than a counter, and that is a durability decision: a pack is
   * identified by its name for all time, and a counter that restarts at one after a redeploy would
   * name a new pack the same as an old one. JGit compares descriptions by name, so the collision
   * would not fail — it would quietly serve the wrong bytes.
   */
  @Override
  protected DfsPackDescription newPack(PackSource source) {
    String name = "pack-" + UUID.randomUUID() + "-" + source.name();
    QitsPackDescription pack =
        new QitsPackDescription(getRepository().getDescription(), name, source);
    // Sorting key for object lookup and for the reftable stack. JGit sets it only in the garbage
    // collector, so a pack written by a push would sort as if it were from 1970 without this.
    pack.setLastModified(System.currentTimeMillis());
    return pack;
  }

  @Override
  protected void commitPackImpl(
      Collection<DfsPackDescription> add, Collection<DfsPackDescription> remove)
      throws IOException {
    catalog.commit(repositoryId, records(add), records(remove));
    clearCache();
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> packs) {
    catalog.rollback(repositoryId, records(packs));
  }

  /** JGit sorts and mutates what it gets back, so this is a fresh mutable list every call. */
  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    List<PackDescription> rows = catalog.list(repositoryId);
    List<DfsPackDescription> packs = new ArrayList<>(rows.size());
    for (PackDescription row : rows) {
      packs.add(QitsPackDescription.fromRecord(getRepository().getDescription(), row));
    }
    return packs;
  }

  @Override
  protected ReadableChannel openFile(DfsPackDescription pack, PackExt ext) throws IOException {
    String blobId = ((QitsPackDescription) pack).blobId(ext);
    if (blobId == null) {
      throw new FileNotFoundException(pack.getFileName(ext));
    }
    return new BlobChannel(blobs.locate(blobId));
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription pack, PackExt ext) throws IOException {
    return new PromoteOnClose((QitsPackDescription) pack, ext, blobs.stage());
  }

  /** {@code -1} when it cannot be answered, which is what {@code ObjectDatabase} documents. */
  @Override
  public long getApproximateObjectCount() {
    try {
      long count = 0;
      for (DfsPackFile pack : getPacks()) {
        count += pack.getPackDescription().getObjectCount();
      }
      return count;
    } catch (IOException e) {
      return -1;
    }
  }

  private static List<PackDescription> records(Collection<DfsPackDescription> packs) {
    if (packs == null) {
      return List.of();
    }
    List<PackDescription> records = new ArrayList<>(packs.size());
    for (DfsPackDescription pack : packs) {
      records.add(((QitsPackDescription) pack).toRecord());
    }
    return records;
  }

  /**
   * A pack file being written into a staged blob, promoted when JGit closes it.
   *
   * <p>{@link #close()} is idempotent and has to be: JGit closes this stream more than once, and a
   * promote that runs twice fails on its own staging area the second time. The symptom of getting
   * that wrong is not a close error — it is {@code UnpackException: Exception while parsing pack
   * stream} on <b>every</b> push, which is a long way from the cause.
   */
  private static final class PromoteOnClose extends DfsOutputStream {

    private final QitsPackDescription pack;
    private final PackExt ext;
    private final PackBlobStore.StagedBlob staged;
    private boolean closed;

    PromoteOnClose(QitsPackDescription pack, PackExt ext, PackBlobStore.StagedBlob staged) {
      this.pack = pack;
      this.ext = ext;
      this.staged = staged;
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
      staged.write(buf, off, len);
    }

    @Override
    public int read(long position, ByteBuffer buf) throws IOException {
      return staged.read(position, buf);
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        pack.blobId(ext, staged.promote());
      } finally {
        staged.close();
      }
    }
  }

  /** JGit's random-access read view of a stored blob. */
  private static final class BlobChannel implements ReadableChannel {

    private final SeekableByteChannel channel;

    BlobChannel(SeekableByteChannel channel) {
      this.channel = channel;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      return channel.read(dst);
    }

    @Override
    public long position() throws IOException {
      return channel.position();
    }

    @Override
    public void position(long newPosition) throws IOException {
      channel.position(newPosition);
    }

    @Override
    public long size() throws IOException {
      return channel.size();
    }

    /** No recommendation: the store below is free to read at any alignment. */
    @Override
    public int blockSize() {
      return 0;
    }

    @Override
    public void setReadAheadBytes(int bufferSize) {
      // Nothing to prefetch: reads go straight at the blob.
    }

    @Override
    public boolean isOpen() {
      return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
