package eu.wohlben.qits.githost.storage;

import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;

/**
 * The ref database: reftables, which are blobs in the same store as the packs.
 *
 * <p>An empty subclass, and that is the entire implementation. It is also the reason the release
 * flow survives this storage change, so it is worth writing down why this and not JGit's other DFS
 * ref backend:
 *
 * <ul>
 *   <li><b>{@code DfsRefDatabase} does not advertise {@code atomic}.</b> Measured, both ways: with
 *       it, {@code git push --atomic} fails with {@code fatal: the receiving end does not support
 *       --atomic push}. {@code ReleaseIntegrator} passes {@code --atomic} for a reason — without it
 *       a duplicate version rejects the tag while its merge commit lands on main.
 *   <li>It also needs <b>more</b> code and a new storage primitive: a ref table plus a
 *       compare-and-swap. Reftable needs neither. Refs ride {@code openFile}/{@code writeFile} like
 *       everything else, so there is one store to back up and one to reason about.
 *   <li>Reflogs come with it. {@code DfsRefDatabase} has none at all.
 * </ul>
 *
 * <p>The price, stated so it is a choice and not a surprise: <b>refs stop being queryable</b>. No
 * {@code SELECT} will ever answer "what is main in repository X" — that goes through JGit. Nothing
 * on this platform asks it in SQL today.
 */
public class QitsDfsReftableDatabase extends DfsReftableDatabase {

  protected QitsDfsReftableDatabase(DfsRepository repository) {
    super(repository);
  }
}
