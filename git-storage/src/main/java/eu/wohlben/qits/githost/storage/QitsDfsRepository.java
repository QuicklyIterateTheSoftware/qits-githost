package eu.wohlben.qits.githost.storage;

import java.io.IOException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;

/**
 * A git repository stored in a blob store and a catalog, with no directory anywhere.
 *
 * <p>It is a {@code Repository}, so {@code UploadPack} and {@code ReceivePack} serve it unchanged —
 * clone, fetch, push, push options and hooks all behave as they do over a bare on a disk. Build one
 * with {@link QitsDfsRepositoryBuilder}.
 *
 * <p>Two things it is <b>not</b>, both of which have caught someone already:
 *
 * <ul>
 *   <li><b>The git CLI cannot open it.</b> There is no directory to point {@code --git-dir} at, no
 *       worktree to add, no config file to write. Every operation on a repository stored this way is
 *       either the wire protocol or in-process JGit; there is no third door. That property is the
 *       point — receive-pack becomes the only writer, so nothing changes a ref without firing {@code
 *       post-receive}.
 *   <li><b>{@link #getConfig()} does not persist.</b> {@code DfsRepository} answers with a {@code
 *       DfsConfig}, whose load and save are no-ops, so a per-repository setting written there is
 *       forgotten immediately and read back as the platform default. Anything that was a line in a
 *       bare's {@code config} needs a row somewhere else.
 * </ul>
 */
public class QitsDfsRepository extends DfsRepository {

  private final String repositoryId;
  private final QitsDfsObjDatabase objects;
  private final QitsDfsReftableDatabase refs;

  QitsDfsRepository(QitsDfsRepositoryBuilder builder) {
    super(builder);
    this.repositoryId = builder.getRepositoryId();
    this.objects =
        new QitsDfsObjDatabase(
            this, builder.getPackBlobStore(), builder.getPackCatalog(), builder.getReaderOptions());
    // Constructed once, here. A getRefDatabase() that returns a new instance per call compiles,
    // serves an advertisement, and then reads refs wrong — the ref cache is per instance.
    this.refs = new QitsDfsReftableDatabase(this);
  }

  /** The key both ports address this repository by. */
  public String getRepositoryId() {
    return repositoryId;
  }

  @Override
  public QitsDfsObjDatabase getObjectDatabase() {
    return objects;
  }

  @Override
  public RefDatabase getRefDatabase() {
    return refs;
  }

  /**
   * Creates the repository with {@code HEAD} pointing at {@code defaultBranch}, which need not
   * exist yet — the shape a freshly provisioned origin has, and what makes the first push a create.
   *
   * <p>{@link #create(boolean)} does the same thing but hardcodes {@code master}, so this exists to
   * keep the platform's {@code main} out of a caller's hands.
   *
   * @throws IOException if the repository already has refs
   */
  public void create(String defaultBranch) throws IOException {
    if (exists()) {
      throw new IOException("git repository " + repositoryId + " already exists");
    }
    RefUpdate.Result result =
        updateRef(Constants.HEAD, true).link(Constants.R_HEADS + defaultBranch);
    if (result != RefUpdate.Result.NEW) {
      throw new IOException("could not point HEAD at " + defaultBranch + ": " + result.name());
    }
  }
}
