package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Deletes every row this host keys by a repository id — the database half of {@code DELETE
 * /git/:repoId}.
 *
 * <p><b>Four tables, one transaction.</b> Packs and pack files are the repository; the protection
 * override and the lines-of-code memos hang off it. They belong to three different stores, and no
 * one of those stores may own the others' rows, so the sweep is a class of its own rather than a
 * method bolted onto {@code CatalogRepository}. Half a deletion is the one outcome worth ruling out:
 * a repository whose packs are gone but whose override remains would hand the next repository
 * created under a reused id somebody else's settings.
 *
 * <p><b>Rows, not bytes.</b> The pack blobs are content-addressed and shared, nothing counts
 * references to them, and the blob store's one delete is reachable only from its sweep. So the
 * repository goes and its bytes stay orphaned — exactly what a repack already leaves behind, at a
 * far smaller rate. See {@link GitPack}.
 *
 * <p>Same threading and patience as every store here: its own request context, because the route's
 * blocking handler binds none, and {@link DbRetry#inNewTx} so a connection lost mid-flight costs the
 * caller a pause rather than an answer. A delete by key is re-runnable — a second execution of it is
 * not a second effect — so the retry is safe wherever the attempt died.
 */
@ApplicationScoped
public class RepositoryPurge {

  /** How long the delete holds while the datasource is gone. The suite shortens it; see the catalog. */
  @ConfigProperty(name = "qits.githost.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /**
   * Deletes the repository's rows and reports whether it was there at all.
   *
   * <p>Existence is the pack rows, the same state {@code DfsGitRepositoryProvider.open} answers off,
   * counted inside the deleting transaction — so the answer cannot be stale by the time it is given.
   *
   * @return {@code true} if the repository existed and is now gone, {@code false} if there was
   *     nothing to delete
   */
  @ActivateRequestContext
  public boolean purge(String repositoryId) {
    return DbRetry.inNewTx(
        "row delete for git repository " + repositoryId,
        () -> deleteRows(repositoryId),
        retryDeadline());
  }

  /**
   * The whole database unit of {@link #purge}, and nothing else — the rule {@code inNewTx} rests on,
   * since the retry re-runs this method.
   *
   * <p>Panache's bulk delete issues its statement at once rather than at commit, so there is nothing
   * here waiting on the far side of the commit boundary and no closing {@code flush} to move it —
   * unlike the row-by-row writes in {@code CatalogRepository} and {@code
   * RepositoryProtectionStore}.
   *
   * <p>Pack files go before packs, the order {@code CatalogRepository.delete} uses: there is no
   * foreign key between them, and keeping the order makes it harmless if one ever appears.
   */
  private boolean deleteRows(String repositoryId) {
    GitPackFile.delete("repositoryId", repositoryId);
    long packs = GitPack.delete("repositoryId", repositoryId);
    GitRepositoryProtection.delete("repositoryId", repositoryId);
    GitRepositoryLoc.delete("repositoryId", repositoryId);
    return packs > 0;
  }

  /**
   * The configured deadline, or the library's default for an instance nobody injected — the shape
   * every store here uses; see {@code CatalogRepository}.
   */
  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
  }
}
