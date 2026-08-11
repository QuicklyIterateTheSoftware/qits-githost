package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.storage.QitsDfsRepository;
import eu.wohlben.qits.githost.storage.QitsDfsRepositoryBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The git host's storage: a repository with no directory anywhere, whose packs, pack indexes and
 * refs are blobs in the platform's own content-addressed store and whose pack list is rows beside
 * them.
 *
 * <p>The only implementation of {@link GitRepositoryProvider}. A second one, bare origins on a
 * shared volume, ran beside it for one release cycle and is gone — the volume with it.
 *
 * <p><b>The git CLI cannot open one of these.</b> There is no directory to point {@code --git-dir}
 * at, no worktree to add and no config file to write, so every operation on such a repository is
 * either the wire protocol or in-process JGit. That is the point rather than a limitation:
 * receive-pack becomes the only writer, so no ref moves without firing {@code post-receive}. It is
 * also why the per-repository protection override had to become a row — {@code
 * DfsRepository.getConfig()} does not persist.
 */
@ApplicationScoped
public class DfsGitRepositoryProvider implements GitRepositoryProvider {

  private static final Logger LOG = Logger.getLogger(DfsGitRepositoryProvider.class);

  @Inject BlobStorePackBlobStore blobs;

  @Inject CatalogRepository catalog;

  /**
   * How long an existence check holds while the datasource is gone. The shipped 15s covers a
   * postgres cutover; the suite shortens it, so a test proving the give-up does not pay for it.
   */
  @ConfigProperty(name = "qits.githost.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /**
   * Existence is answered by the ref database, not by a table of its own: a repository that has ever
   * been created has a reftable in the catalog, and one that has not reads as empty. So an unknown
   * id is a 404 with no extra row to keep in step.
   *
   * <p><b>Null means one thing: the catalog answered, and it holds no such repository.</b> A catalog
   * that cannot answer throws instead, so the routes make a 500 of it. Reading a failed read as
   * absence cost a platform bootstrap: postgres was cut over mid-run, the severed pool made the
   * existence check throw, and a push to a repository that exists was told 404.
   *
   * <p><b>Patience first, then that honest failure.</b> This is the incident's own seam, so the
   * check is wrapped in {@link DbRetry}: a connection lost mid-flight costs the request a pause
   * instead of an answer. It is a read, so re-running it is safe. Nothing is softened — after the
   * deadline the exception propagates exactly as before, and the route still answers 500.
   */
  @Override
  public Repository open(String repoId) {
    // Each attempt builds its own repository, so no attempt inherits state a half-read one cached,
    // and the whole of it sits OUTSIDE the catalog's transactions — see CatalogRepository, whose
    // own reads retry within the same deadline rather than adding a second one to it.
    return DbRetry.call(
        "existence check for git repository " + repoId, () -> openOnce(repoId), dbRetryDeadline);
  }

  /** One attempt at {@link #open}: build, ask, and close whatever is not handed back. */
  private Repository openOnce(String repoId) {
    QitsDfsRepository repo = build(repoId);
    boolean exists;
    try {
      exists = repo.exists();
    } catch (IOException | RuntimeException e) {
      repo.close();
      LOG.warnf(e, "the pack catalog could not say whether git repository %s exists", repoId);
      throw unchecked(repoId, e);
    }
    if (exists) {
      return repo;
    }
    repo.close();
    return null;
  }

  /**
   * Passes a runtime failure through unchanged, and makes a checked one throwable from a port method
   * that declares nothing.
   */
  private static RuntimeException unchecked(String repoId, Exception e) {
    return e instanceof RuntimeException runtime
        ? runtime
        : new UncheckedIOException(
            "cannot read git repository " + repoId + " from the pack catalog", (IOException) e);
  }

  /**
   * The catalog's own repository ids. Nothing is opened and no blob is read — enumerating a host is
   * one query, not one repository open per row.
   *
   * <p>No retry here: the one query holds through a short outage in the catalog itself.
   */
  @Override
  public List<String> repositoryIds() {
    return catalog.repositoryIds();
  }

  @Override
  public void create(String repoId, String defaultBranch) throws IOException {
    try (QitsDfsRepository repo = build(repoId)) {
      repo.create(defaultBranch);
    }
  }

  /**
   * Opening reads nothing — no blob is fetched and no row is read until something asks for an object
   * or a ref — so one per request is the right shape, exactly as a bare is opened per request today.
   */
  private QitsDfsRepository build(String repoId) {
    return new QitsDfsRepositoryBuilder()
        .setRepositoryId(repoId)
        .setPackBlobStore(blobs)
        .setPackCatalog(catalog)
        .build();
  }
}
