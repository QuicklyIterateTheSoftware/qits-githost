package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.githost.storage.PackCatalog;
import eu.wohlben.qits.githost.storage.PackDescription;
import eu.wohlben.qits.githost.storage.PackFile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * {@link PackCatalog} over two Panache entities — the git host's half of the storage unification,
 * and one of the two adapters that can only live in this module.
 *
 * <p>{@code git-storage} may not depend on {@code qits-artifacts-artifacts} and {@code artifacts}
 * may not depend on {@code git-storage} — they are different contexts — so the port is declared
 * there and implemented here, in the one module that already depends on both. Nothing crossing the
 * port is a JGit type, which is what keeps this class out of the blast radius of a JGit upgrade.
 *
 * <p><b>Rows, not bytes.</b> A pack's files are blobs; this holds the list. Committing a description
 * is what makes bytes visible and dropping one is what makes them invisible, and dropping one frees
 * nothing at all — the blob store has no delete, which is the recorded posture rather than an
 * omission (see {@link GitPack}).
 *
 * <h2>Threading</h2>
 *
 * <p>Every method here runs on a Vert.x worker thread with <b>no request context and no
 * transaction</b> bound: JGit reaches the catalog from inside {@code UploadPack}/{@code
 * ReceivePack}, which {@code GitHostRoutes} drives from a blocking handler. So each method activates
 * its own request context and opens its own transaction, the same shape the {@code
 * RepositoryNameResolver} port documents. Inside a {@code @QuarkusTest} the request context is
 * already active and the annotation is a no-op.
 */
@ApplicationScoped
public class CatalogRepository implements PackCatalog {

  private static final Logger LOG = Logger.getLogger(CatalogRepository.class);

  @Override
  @ActivateRequestContext
  public List<PackDescription> list(String repositoryId) {
    return QuarkusTransaction.requiringNew().call(() -> read(repositoryId));
  }

  /**
   * Adds and removes in <b>one</b> transaction, because a reader that saw only half of a repack
   * would find objects in no pack at all. Removes go first: a name is never reused, so the two sets
   * cannot overlap, but doing it in this order makes that assumption harmless if it ever stops
   * holding.
   */
  @Override
  @ActivateRequestContext
  public void commit(
      String repositoryId, Collection<PackDescription> add, Collection<PackDescription> remove) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (PackDescription pack : remove) {
                delete(repositoryId, pack.packName());
              }
              for (PackDescription pack : add) {
                insert(repositoryId, pack);
              }
            });
  }

  /**
   * There is normally nothing to undo — a pack is invisible until {@link #commit} — so this is a log
   * line and its blobs are simply never referenced again. It <b>cannot fail</b>: it is called only
   * when a write already failed, and throwing here would replace the real cause with this one.
   */
  @Override
  public void rollback(String repositoryId, Collection<PackDescription> packs) {
    if (packs == null || packs.isEmpty()) {
      return;
    }
    List<String> names = new ArrayList<>(packs.size());
    for (PackDescription pack : packs) {
      names.add(pack.packName());
    }
    // Their blobs may already be promoted and stay in the store forever. That is the no-delete
    // posture, not a leak to fix — the bytes are unreferenced and immaterial at three per push.
    LOG.debugf("abandoned %d uncommitted pack(s) in git repository %s: %s",
        names.size(), repositoryId, names);
  }

  private List<PackDescription> read(String repositoryId) {
    List<GitPack> packs = GitPack.list("repositoryId", repositoryId);
    if (packs.isEmpty()) {
      // An unknown repository has no packs. That is an empty list rather than an error, and it is
      // how a repository that has never been pushed to reads.
      return List.of();
    }
    List<GitPackFile> files = GitPackFile.list("repositoryId", repositoryId);
    List<PackDescription> descriptions = new ArrayList<>(packs.size());
    for (GitPack pack : packs) {
      List<PackFile> packFiles = new ArrayList<>(2);
      for (GitPackFile file : files) {
        if (file.packName.equals(pack.packName)) {
          packFiles.add(new PackFile(file.extension, file.blobId, file.size, file.blockSize));
        }
      }
      descriptions.add(
          new PackDescription(
              pack.packName,
              pack.source,
              pack.lastModified,
              pack.objectCount,
              pack.deltaCount,
              pack.minUpdateIndex,
              pack.maxUpdateIndex,
              pack.indexVersion,
              packFiles));
    }
    return descriptions;
  }

  private void insert(String repositoryId, PackDescription description) {
    GitPack pack = new GitPack();
    pack.repositoryId = repositoryId;
    pack.packName = description.packName();
    pack.source = description.source();
    pack.lastModified = description.lastModified();
    pack.objectCount = description.objectCount();
    pack.deltaCount = description.deltaCount();
    pack.minUpdateIndex = description.minUpdateIndex();
    pack.maxUpdateIndex = description.maxUpdateIndex();
    pack.indexVersion = description.indexVersion();
    pack.persist();
    for (PackFile file : description.files()) {
      GitPackFile row = new GitPackFile();
      row.repositoryId = repositoryId;
      row.packName = description.packName();
      row.extension = file.extension();
      row.blobId = file.blobId();
      row.size = file.size();
      row.blockSize = file.blockSize();
      row.persist();
    }
  }

  /** Matched by pack name, as the port specifies. A name that is not listed is not an error. */
  private void delete(String repositoryId, String packName) {
    GitPackFile.delete("repositoryId = ?1 and packName = ?2", repositoryId, packName);
    GitPack.delete("repositoryId = ?1 and packName = ?2", repositoryId, packName);
  }
}
