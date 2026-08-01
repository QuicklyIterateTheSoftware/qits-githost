package eu.wohlben.qits.githost.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * Where a repository's bytes live: pack files, pack indexes and reftables, each one blob.
 *
 * <p>One of the two ports this module declares. It is written against a <b>content-addressed</b>
 * store — the address is the store's answer, not the caller's choice — because that is the shape
 * qits-artifacts' {@code BlobStore} already has, and because identical bytes then cost one blob.
 *
 * <p>Two requirements come straight from JGit and neither is negotiable:
 *
 * <ul>
 *   <li>A blob being written must be <b>readable while it is written</b>. JGit's pack parser reads
 *       back deltas from a pack it has not finished storing, so {@link StagedBlob} carries a
 *       positional {@link StagedBlob#read read} beside its append-only {@link StagedBlob#write
 *       write}.
 *   <li>A stored blob must support <b>random access</b>, not streaming: {@link #locate} returns a
 *       seekable channel, which is what makes serving a clone out of one large pack cheap.
 * </ul>
 *
 * <p><b>There is no delete.</b> That is the platform's recorded posture, not an omission — the git
 * host never runs garbage collection in production, because a repack in a store that cannot delete
 * writes the new pack and keeps the old ones (measured: one run took a repository from 7.8 MB to 15
 * MB). An implementation is free to grow forever; roughly three blobs per push is the rate.
 *
 * <p>The types here are the JDK's on purpose. No method hands out anything from {@code
 * org.eclipse.jgit.internal.*}, so an adapter is not exposed to JGit's internal API — see this
 * module's README.
 */
public interface PackBlobStore {

  /**
   * Opens the blob at {@code blobId} for random-access reading. The caller closes the channel.
   *
   * @param blobId a content address this store returned from {@link StagedBlob#promote}
   * @throws java.io.FileNotFoundException if this store holds no such blob
   * @throws IOException if it cannot be opened
   */
  SeekableByteChannel locate(String blobId) throws IOException;

  /**
   * Begins a new blob. The bytes are not visible to {@link #locate} until {@link StagedBlob#promote}
   * returns.
   */
  StagedBlob stage() throws IOException;

  /**
   * A blob being written. Append with {@link #write}, read back what was written with {@link #read},
   * then either {@link #promote} it into the store or {@link #close} to throw it away.
   *
   * <p>Not thread-safe, and not expected to be: one pack, one writer.
   */
  interface StagedBlob extends AutoCloseable {

    /** Appends {@code len} bytes at the current end of the blob. */
    void write(byte[] buf, int off, int len) throws IOException;

    /**
     * Reads up to {@code dst.remaining()} bytes from {@code position} of what has been written so
     * far. Does not move the write position.
     *
     * @return the number of bytes read, or {@code -1} past the end
     */
    int read(long position, ByteBuffer dst) throws IOException;

    /**
     * Finishes the blob and returns its content address.
     *
     * <p>Storing bytes that are already stored is <b>not</b> an error: it returns the same address
     * and keeps one copy.
     */
    String promote() throws IOException;

    /**
     * Releases the staging area. Called exactly once, always, including after {@link #promote} — so
     * it must be a no-op on an already-promoted blob and must discard an unpromoted one.
     */
    @Override
    void close() throws IOException;
  }
}
