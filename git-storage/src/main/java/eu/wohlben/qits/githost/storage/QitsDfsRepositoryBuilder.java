package eu.wohlben.qits.githost.storage;

import java.util.Objects;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;

/**
 * Builds a {@link QitsDfsRepository} from a repository id and the two ports.
 *
 * <p>Opening a repository is cheap and reads nothing: no blob is fetched and no catalog row is read
 * until something asks for an object or a ref. So the caller opens one per request and closes it,
 * the same way {@code GitHostRoutes} opens a bare today.
 *
 * <p>Repositories are addressed by id alone. The id is not a path — nothing here joins it to a
 * directory — so it needs no traversal defence of its own, though whatever hands it in should still
 * be sure of where it came from.
 */
public class QitsDfsRepositoryBuilder
    extends DfsRepositoryBuilder<QitsDfsRepositoryBuilder, QitsDfsRepository> {

  private String repositoryId;
  private PackBlobStore blobs;
  private PackCatalog catalog;

  /** Also becomes the repository description, which keys JGit's block cache. */
  public QitsDfsRepositoryBuilder setRepositoryId(String repositoryId) {
    this.repositoryId = repositoryId;
    return setRepositoryDescription(new DfsRepositoryDescription(repositoryId));
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public QitsDfsRepositoryBuilder setPackBlobStore(PackBlobStore blobs) {
    this.blobs = blobs;
    return self();
  }

  public PackBlobStore getPackBlobStore() {
    return blobs;
  }

  public QitsDfsRepositoryBuilder setPackCatalog(PackCatalog catalog) {
    this.catalog = catalog;
    return self();
  }

  public PackCatalog getPackCatalog() {
    return catalog;
  }

  @Override
  public QitsDfsRepository build() {
    Objects.requireNonNull(repositoryId, "repositoryId");
    Objects.requireNonNull(blobs, "packBlobStore");
    Objects.requireNonNull(catalog, "packCatalog");
    if (getReaderOptions() == null) {
      setReaderOptions(new DfsReaderOptions());
    }
    return new QitsDfsRepository(this);
  }
}
