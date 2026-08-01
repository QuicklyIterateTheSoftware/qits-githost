package eu.wohlben.qits.githost.storage;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Which packs a repository has. The blob store holds the bytes; this holds the list.
 *
 * <p>The second of the two ports this module declares. It is the only mutable state a repository
 * has: a pack's blobs are content-addressed and immutable, so committing a pack description is what
 * makes bytes visible, and dropping one is what makes them invisible. <b>Dropping a description
 * frees nothing</b> — the blobs stay, because the store has no delete (see {@link PackBlobStore}).
 *
 * <p>A repository is a {@code repositoryId} and nothing else. This module never interprets that
 * string; it does not have to be a path, a name or a uuid, and two repositories that share a
 * catalog are separated by it alone.
 *
 * <p>Ordering is not part of the contract: JGit sorts what {@link #list} returns, by fields carried
 * in {@link PackDescription}. Durability is: a description that {@link #commit} accepted must come
 * back from {@link #list} after a restart, with every field intact.
 */
public interface PackCatalog {

  /**
   * Every pack of one repository. An unknown repository has no packs — that is an empty list, not an
   * error, and it is how a repository that has never been pushed to reads.
   */
  List<PackDescription> list(String repositoryId) throws IOException;

  /**
   * Adds packs and removes packs, as one step.
   *
   * <p>The two happen together on purpose: a repack commits the new pack and drops the packs it
   * replaces, and a reader that saw neither state would find objects in no pack at all. An
   * implementation with transactions should use one.
   *
   * @param add descriptions to make visible; may be empty
   * @param remove descriptions to stop listing, matched by {@link PackDescription#packName}; may be
   *     empty. A name that is not listed is not an error.
   */
  void commit(
      String repositoryId, Collection<PackDescription> add, Collection<PackDescription> remove)
      throws IOException;

  /**
   * Abandons packs that were written but never committed.
   *
   * <p>Called only when a write already failed, which is why it cannot fail in turn and why it
   * throws nothing: an implementation logs and returns. There is normally nothing to undo — a pack
   * is invisible until {@link #commit} — so the honest implementation of this method is a note in a
   * log, and its blobs are simply never referenced again.
   */
  void rollback(String repositoryId, Collection<PackDescription> packs);
}
