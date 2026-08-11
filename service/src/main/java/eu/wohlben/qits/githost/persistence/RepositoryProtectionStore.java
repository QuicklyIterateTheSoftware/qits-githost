package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reads and writes the per-repository protection override — one mechanism for both storage backends.
 *
 * <p>Called from {@code ProtectedRefHook} on a Vert.x worker thread inside {@code
 * ReceivePack.receive}, with no request context and no transaction bound, so it opens both for
 * itself. Inside a {@code @QuarkusTest} the request context is already active and the annotation is
 * a no-op.
 *
 * <p><b>An unreadable answer is not a decision.</b> A failed read falls back to the platform default
 * rather than to "protected" or "unprotected", for the same reason the config read it replaced did:
 * this service is the git host that serves the push that redeploys the git host, and a storage
 * hiccup that started refusing pushes would strand exactly the fix for it.
 *
 * <p><b>The two halves are patient in opposite directions, on purpose.</b> The read is fail-soft and
 * gets no retry — it already has an answer for a database that will not talk, and holding a push for
 * fifteen seconds to reach the same fallback would be worse than the fallback. The write has no
 * fallback at all, so it is the one that waits.
 */
@ApplicationScoped
public class RepositoryProtectionStore {

  private static final Logger LOG = Logger.getLogger(RepositoryProtectionStore.class);

  /** How long the write holds while the datasource is gone. The suite shortens it; see the catalog. */
  @ConfigProperty(name = "qits.githost.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /** The override for one repository, or empty when there is none. */
  @ActivateRequestContext
  public Optional<Boolean> protectionOverride(String repositoryId) {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                GitRepositoryProtection row =
                    GitRepositoryProtection.findById(repositoryId);
                return row == null ? Optional.<Boolean>empty() : Optional.of(row.protectDefaultBranch);
              });
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not read the protection override of %s; using the platform default",
          repositoryId);
      return Optional.empty();
    }
  }

  /**
   * Sets (or replaces) the override. There is no "unset" verb because nothing needs one yet.
   *
   * <p>Held through a short outage by {@link DbRetry#runInNewTx}, which opens the transaction per
   * attempt and retries only what certainly did not commit. The write is an upsert on the primary
   * key, so a second execution of it is not a second effect either way — the strict form is used
   * because it costs nothing here and it is the shape the next write on this class will want.
   */
  @ActivateRequestContext
  public void setProtectionOverride(String repositoryId, boolean protectDefaultBranch) {
    DbRetry.runInNewTx(
        "protection override for git repository " + repositoryId,
        () -> writeOverride(repositoryId, protectDefaultBranch),
        dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline);
  }

  /**
   * The database unit of {@link #setProtectionOverride}, and nothing else — the rule {@code
   * runInNewTx} rests on, since the retry re-runs it. The closing {@code flush} is what puts the
   * insert-or-update in the statement phase instead of at commit, where a lost connection is
   * undecidable; {@code updatedAt} is re-read per attempt, which is correct — it is when the row was
   * written, not when the call started.
   */
  private void writeOverride(String repositoryId, boolean protectDefaultBranch) {
    GitRepositoryProtection row = GitRepositoryProtection.findById(repositoryId);
    if (row == null) {
      row = new GitRepositoryProtection();
      row.repositoryId = repositoryId;
    }
    row.protectDefaultBranch = protectDefaultBranch;
    row.updatedAt = Instant.now();
    row.persist();
    GitRepositoryProtection.getEntityManager().flush();
  }
}
