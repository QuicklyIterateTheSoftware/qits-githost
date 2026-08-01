package eu.wohlben.qits.githost.storage;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link PackBlobStore} in a map: sha-256 of the bytes to the bytes.
 *
 * <p>The same shape as qits-artifacts' {@code BlobStore} — content-addressed, deduplicating, and
 * <b>with no delete</b> — so a test that measures how the store grows measures what the deployment
 * would do. It is not a stub with hardcoded answers: everything the suite proves, it proves against
 * a store that really hashes and really dedupes.
 */
final class InMemoryPackBlobStore implements PackBlobStore {

  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

  @Override
  public SeekableByteChannel locate(String blobId) throws IOException {
    byte[] bytes = blobs.get(blobId);
    if (bytes == null) {
      throw new FileNotFoundException(blobId);
    }
    return new ByteArrayChannel(bytes);
  }

  @Override
  public StagedBlob stage() {
    return new Staged();
  }

  /** How many distinct blobs the store holds — the count that never goes down. */
  int blobCount() {
    return blobs.size();
  }

  long byteCount() {
    return blobs.values().stream().mapToLong(b -> b.length).sum();
  }

  private final class Staged implements StagedBlob {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private byte[] snapshot;

    @Override
    public void write(byte[] buf, int off, int len) {
      snapshot = null;
      buffer.write(buf, off, len);
    }

    @Override
    public int read(long position, ByteBuffer dst) {
      byte[] written = snapshot();
      int available = (int) (written.length - position);
      if (available <= 0) {
        return -1;
      }
      int n = Math.min(dst.remaining(), available);
      dst.put(written, (int) position, n);
      return n;
    }

    @Override
    public String promote() {
      byte[] written = snapshot();
      String blobId = sha256(written);
      blobs.putIfAbsent(blobId, written);
      return blobId;
    }

    @Override
    public void close() {
      snapshot = null;
    }

    private byte[] snapshot() {
      if (snapshot == null) {
        snapshot = buffer.toByteArray();
      }
      return snapshot;
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Random access over a stored blob, which is what {@code locate} has to hand out. */
  private static final class ByteArrayChannel implements SeekableByteChannel {

    private final byte[] bytes;
    private int position;
    private boolean open = true;

    ByteArrayChannel(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public int read(ByteBuffer dst) {
      int available = bytes.length - position;
      if (available <= 0) {
        return -1;
      }
      int n = Math.min(dst.remaining(), available);
      dst.put(bytes, position, n);
      position += n;
      return n;
    }

    @Override
    public int write(ByteBuffer src) {
      throw new UnsupportedOperationException("a promoted blob is immutable");
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) {
      position = (int) newPosition;
      return this;
    }

    @Override
    public long size() {
      return bytes.length;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
      throw new UnsupportedOperationException("a promoted blob is immutable");
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }
}
