package eu.wohlben.qits.githost;

import java.util.Optional;

/**
 * Port: resolves a project-scoped repository <b>name</b> to the repo id this context serves — the
 * one fact {@link GitHostRoutes} reads of another context.
 *
 * <p>In the monorepo this was a direct injection of {@code
 * eu.wohlben.qits.domain.repository.persistence.RepositoryNameRepository}, the alias table owned by
 * the projects/repositories context. That table is not this context's, and this repo holds no
 * foreign key into another context's schema, so the reach-out became this port and the consuming
 * application implements it (typically over that same alias table).
 *
 * <p><b>Optional</b> ({@code Instance<>}; absent is a supported configuration). Without an
 * implementation the name-addressed scheme {@code /git/:projectId/:repoName} answers 404
 * and the id-addressed scheme {@code /git/:repoId} — internal storage plumbing now — keeps working
 * unchanged.
 *
 * <p><b>A miss and an outage are different answers, and that is the whole of this contract.</b>
 * Empty means the projects service ANSWERED, and its answer was "no such name" — a 404 to the git
 * client, which caches it as fact. Anything that stopped the question being answered at all — a
 * timeout, a refused connection, a non-200, an unreadable body — throws {@link Unavailable}, which
 * {@link GitHostRoutes} turns into a 503. That is {@code fe26a6c}'s lesson one surface further out:
 * a read that failed must never be reported as an absent thing.
 *
 * <p>Called on a Vert.x worker thread with no request context bound, so an implementation that
 * reads a database must open its own transaction (the monorepo's inline {@code
 * QuarkusTransaction.requiringNew()} moved here with the lookup).
 */
public interface RepositoryNameResolver {

  /**
   * @param projectId the owning project's id, taken verbatim from the url segment
   * @param name the repository name, already stripped of a trailing {@code .git}
   * @return the repo id to serve, or empty if the project has no repository under that name. The
   *     returned id is re-validated against the repo-id slug before it touches the filesystem.
   * @throws Unavailable if the question could not be answered at all
   */
  Optional<String> resolveRepositoryId(String projectId, String name);

  /**
   * The lookup could not be made. Unchecked, because there is nothing a route can do about it except
   * say so: {@link GitHostRoutes} answers 503, and a git client retries a 503 rather than recording
   * that the repository is gone.
   *
   * <p>It is part of the PORT rather than of the HTTP adapter, because the distinction it draws —
   * "answered no" against "did not answer" — is what every implementation owes its caller.
   */
  class Unavailable extends RuntimeException {

    public Unavailable(String message) {
      super(message);
    }

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
