package eu.wohlben.qits.githost.persistence;

import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
 *
 * <p><b>{@link #protectionOverrides} is the third, and it belongs to a reader rather than to a
 * push.</b> It waits like the write and fails like it, because the API that calls it is answering a
 * person: a fallback there would be a guess on screen with nothing marking it as one.
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
   * Every override this store holds, by repository id — one query for a whole page, which is what
   * makes the field cheap enough for {@code GET /githost/api/repositories} to carry.
   *
   * <p><b>It throws where {@link #protectionOverride} falls back, and the difference is the
   * caller.</b> That one is asked inside a push, where an unreadable answer must not decide the
   * question and the platform default is the safe way through. This one is asked by a reader waiting
   * for an answer, so "I could not ask" has to reach them as a failure — softened, it would put a
   * value on screen that is a guess with nothing marking it as one.
   *
   * <p>Held through a lost connection by {@link DbRetry#call}, the read seam, with the retry
   * <b>outside</b> {@code requiringNew} so every attempt runs on a freshly borrowed connection.
   *
   * <p>The table holds overrides only — a repository with no row is not in it — so the answer is
   * small even where the host serves many repositories, and a missing key means "no override", not
   * "unknown".
   */
  @ActivateRequestContext
  public Map<String, Boolean> protectionOverrides() {
    return DbRetry.call(
        "protection override enumeration",
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () -> {
                      Map<String, Boolean> byRepository = new HashMap<>();
                      for (GitRepositoryProtection row :
                          GitRepositoryProtection.<GitRepositoryProtection>listAll()) {
                        byRepository.put(row.repositoryId, row.protectDefaultBranch);
                      }
                      return byRepository;
                    }),
        retryDeadline());
  }

  /**
   * The configured deadline, or the library's default for an instance nobody injected — which is how
   * a test's {@code QuarkusMock} subclass arrives, built with {@code new} and calling {@code super}
   * for the part it does not fake. Same shape as {@code CatalogRepository}'s.
   */
  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
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
        retryDeadline());
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
