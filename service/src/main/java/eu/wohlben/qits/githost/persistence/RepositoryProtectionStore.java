package eu.wohlben.qits.githost.persistence;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Instant;
import java.util.Optional;
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
 */
@ApplicationScoped
public class RepositoryProtectionStore {

  private static final Logger LOG = Logger.getLogger(RepositoryProtectionStore.class);

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

  /** Sets (or replaces) the override. There is no "unset" verb because nothing needs one yet. */
  @ActivateRequestContext
  public void setProtectionOverride(String repositoryId, boolean protectDefaultBranch) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              GitRepositoryProtection row = GitRepositoryProtection.findById(repositoryId);
              if (row == null) {
                row = new GitRepositoryProtection();
                row.repositoryId = repositoryId;
              }
              row.protectDefaultBranch = protectDefaultBranch;
              row.updatedAt = Instant.now();
              row.persist();
            });
  }
}
