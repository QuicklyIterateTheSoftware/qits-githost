package eu.wohlben.qits.githost.api;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.persistence.RepositoryProtectionStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@code GET /githost/api/repositories} — the catalogue the git host's own client reads.
 *
 * <p><b>It is a STORAGE view, and the ids it answers are opaque storage keys.</b> This host keys
 * repositories by the UUID qits-projects mints and holds no name for any of them; the public
 * identity of a repository is {@code (projectId, repoName)} and lives in qits-projects. So a row
 * here says what bytes this host holds, and it is <b>not</b> a clone url: {@code
 * /git/<repoId>} is the internal storage scheme, served to qits-projects' own client. Anything that
 * wants to name, link to or clone a repository asks qits-projects.
 *
 * <p><b>Why this exists beside {@code GET /git}.</b> Both are storage views; that one is a wire the
 * platform's machines read, with its prefix a literal because git owns the spelling, and this is the
 * browser's surface, under the service's gateway segment with the SPA. It answers <b>records</b> —
 * so a field can be added here without touching a contract every machine caller depends on.
 *
 * <p><b>The contract with qits-spa-githost is one field.</b> {@code id} is guaranteed; the client
 * renders whatever else arrives as a label and a value and assumes nothing about it. So a field is
 * added here when it is honest and cheap, and never as a placeholder — an empty column is the client
 * claiming the service answered "nothing" when it answered nothing at all.
 *
 * <p><b>A failed read is a 5xx, never an empty list and never a 404.</b> This service learned that
 * on 2026-08-11: a severed connection pool made {@code DfsGitRepositoryProvider.open} read a
 * database it could not reach as "no such repository", and every caller downstream treated the 404
 * as fact (fixed in {@code fe26a6c}). Both reads below therefore throw rather than fall back — the
 * patience that precedes the failure is {@code DbRetry}, inside the two stores, and what outlives
 * its deadline arrives here as an exception this class turns into a 500.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
public class RepositoriesResource {

  private static final Logger LOG = Logger.getLogger(RepositoriesResource.class);

  /**
   * The id filter, the same rule {@code GitHostRoutes} applies to its own listing: an id that is not
   * a valid slug is served by no route on this host, so listing one would advertise a repository
   * nobody could clone.
   */
  private static final String REPO_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** Where the repositories come from: the pack catalog, through the storage port. */
  @Inject GitRepositoryProvider repositories;

  /** The per-repository protection overrides, read once for the whole page. */
  @Inject RepositoryProtectionStore protections;

  /**
   * The platform-wide protection switch, and the value a repository with no override row carries.
   * The same key {@code ProtectedRefHook} decides a push by, read here so the page shows what a
   * pusher would actually meet rather than only whether a row exists.
   */
  @ConfigProperty(name = "qits.repositories.git.protect-default-branch", defaultValue = "false")
  boolean protectByDefault;

  /**
   * One repository, as this API spells it.
   *
   * <p>{@code defaultBranch} is deliberately absent: reading it means opening the repository and
   * resolving its {@code HEAD}, which is one storage round trip per row, and this list is drawn for
   * a human. {@code GET /git/:repoId} answers it for one repository, which is where a caller that
   * needs it should ask.
   */
  public record RepositoryRecord(String id, boolean protectDefaultBranch) {}

  /** The envelope. The field name is the contract; the client reads {@code repositories}. */
  public record RepositoriesResponse(List<RepositoryRecord> repositories) {}

  /**
   * Every repository this host serves, sorted lexicographically.
   *
   * <p>Two reads, both of which must succeed: the catalog enumeration and the one query that fetches
   * every protection override. Sorting and filtering happen here rather than in a store, so the
   * order and the slug rule are properties of this response.
   */
  @GET
  public RepositoriesResponse list() {
    List<String> ids;
    try {
      ids = repositories.repositoryIds();
    } catch (Exception e) {
      throw unavailable("could not enumerate the git repositories", e);
    }

    Map<String, Boolean> overrides;
    try {
      overrides = protections.protectionOverrides();
    } catch (Exception e) {
      // Not softened to the platform default, although ProtectedRefHook does exactly that for a
      // push. There the fallback keeps the host serving; here it would put a number on screen that
      // is a guess, and a reader cannot tell a guess from an answer.
      throw unavailable("could not read the repository protection overrides", e);
    }

    List<RepositoryRecord> records = new ArrayList<>(ids.size());
    ids.stream()
        .filter(id -> id.matches(REPO_ID_PATTERN))
        .sorted()
        .forEach(
            id ->
                records.add(
                    new RepositoryRecord(id, overrides.getOrDefault(id, protectByDefault))));
    return new RepositoriesResponse(records);
  }

  /**
   * Logs the cause and answers 500. The cause is logged rather than sent: it names a datasource and
   * a driver, and this listing is served unauthenticated (the per-repository browse paths beside it
   * are role-gated — see the {@code githost-browse} policy).
   */
  private ServerErrorException unavailable(String what, Exception cause) {
    LOG.error(what, cause);
    return new ServerErrorException(what, Response.Status.INTERNAL_SERVER_ERROR, cause);
  }
}
