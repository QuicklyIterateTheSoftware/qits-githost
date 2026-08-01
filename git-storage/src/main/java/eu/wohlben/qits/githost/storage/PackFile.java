package eu.wohlben.qits.githost.storage;

import java.util.Objects;

/**
 * One file of a pack, and the blob that holds it.
 *
 * @param extension the file extension JGit knows the file by — {@code pack}, {@code idx}, {@code
 *     ref} (a reftable's {@code PackExt.REFTABLE} spells its extension {@code ref}, measured through
 *     the adapter, not {@code reftable}), {@code bitmap}, and whatever a later JGit adds. A
 *     <b>string</b>, deliberately: a
 *     catalog row must not carry a JGit enum, or a JGit upgrade that renames one silently changes
 *     what stored rows mean.
 * @param blobId the content address {@link PackBlobStore.StagedBlob#promote} returned
 * @param size the file's size in bytes, {@code 0} if it was not known when the pack was committed
 * @param blockSize the store's preferred read alignment for the file, {@code 0} if none
 */
public record PackFile(String extension, String blobId, long size, int blockSize) {

  public PackFile {
    Objects.requireNonNull(extension, "extension");
    Objects.requireNonNull(blobId, "blobId");
  }
}
