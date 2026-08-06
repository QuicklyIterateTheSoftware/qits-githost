package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.storage.QitsDfsRepository;
import eu.wohlben.qits.githost.storage.QitsDfsRepositoryBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
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
   * Existence is answered by the ref database, not by a table of its own: a repository that has ever
   * been created has a reftable in the catalog, and one that has not reads as empty. So an unknown
   * id is a 404 with no extra row to keep in step.
   */
  @Override
  public Repository open(String repoId) {
    QitsDfsRepository repo = build(repoId);
    try {
      if (repo.exists()) {
        return repo;
      }
    } catch (Exception e) {
      LOG.debugf(e, "git repository %s could not be read from the pack catalog", repoId);
    }
    repo.close();
    return null;
  }

  /**
   * The catalog's own repository ids. Nothing is opened and no blob is read — enumerating a host is
   * one query, not one repository open per row.
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
