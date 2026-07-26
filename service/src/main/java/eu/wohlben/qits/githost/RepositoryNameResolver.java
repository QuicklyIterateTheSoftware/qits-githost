package eu.wohlben.qits.githost;

import java.util.Optional;

/**
 * Port: resolves a project-scoped repository <b>name</b> to the repo id whose bare origin this
 * context serves — the one fact {@link GitHostRoutes} reads of another context.
 *
 * <p>In the monorepo this was a direct injection of {@code
 * eu.wohlben.qits.domain.repository.persistence.RepositoryNameRepository}, the alias table owned by
 * the projects/repositories context. That table is not this context's, and this repo holds no
 * foreign key into another context's schema, so the reach-out became this port and the consuming
 * application implements it (typically over that same alias table).
 *
 * <p><b>Optional</b> ({@code Instance<>}; absent is a supported configuration). Without an
 * implementation the name-addressed scheme {@code /git/:projectId/:repoName} answers 404 and the
 * id-addressed scheme {@code /git/:repoId} — the older of the two, and the daemon's existing
 * fallback — keeps working unchanged.
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
   */
  Optional<String> resolveRepositoryId(String projectId, String name);
}
