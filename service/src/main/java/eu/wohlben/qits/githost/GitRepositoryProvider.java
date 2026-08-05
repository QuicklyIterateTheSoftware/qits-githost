package eu.wohlben.qits.githost;

import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.Repository;

/**
 * Port: turns a repo id into an open {@link Repository}. The <b>one</b> seam between the git host's
 * wire protocol and where its bytes actually live.
 *
 * <p>Everything above this is backend-agnostic already and stays untouched: {@code
 * UploadPack}/{@code ReceivePack} take a {@code Repository}, so {@code GitHostRoutes.infoRefs} and
 * {@code GitHostRoutes.service} cannot tell a bare on a filesystem from a repository whose packs and
 * refs are blobs. Only opening it differs, which is why this interface has as few methods as it
 * does.
 *
 * <p>Two implementations ship in the same binary and {@code qits.repositories.git.storage} picks one
 * at runtime — see {@link GitRepositoryBackend}. Both stay, for one full release cycle after the
 * rollout at least: the git host serves the push that redeploys the git host, and that is precisely
 * the place where an irreversible cutover is a bad idea.
 */
public interface GitRepositoryProvider {

  /**
   * The value of {@code qits.repositories.git.storage} that selects this implementation — {@code
   * file} or {@code dfs}. Declared by the implementation rather than by the selector so that adding
   * a backend is adding one class.
   */
  String name();

  /**
   * Opens the repository, or returns {@code null} if this backend holds no such repository — which
   * the routes answer as a 404, the same answer an id that is not a valid slug gets. The caller
   * closes what it gets back.
   *
   * <p>Never throws: a repository that exists but will not open is a deployment fact this process
   * cannot act on, so it is logged at debug and reads as absent. That distinction has already
   * mattered once — a native build in which JGit could open nothing looked exactly like a host full
   * of unknown ids.
   */
  Repository open(String repoId);

  /**
   * Creates an empty repository whose {@code HEAD} names {@code defaultBranch}, which need not exist
   * yet — the shape a freshly provisioned origin has, and what makes the first push a create.
   *
   * <p>{@code PUT /artifacts/git/:repoId} ({@link GitHostRoutes}) is the route that calls this — the
   * git-host lifecycle API ({@code projects-volume-decoupling-plan.md} §2) that replaced "creation
   * reaches this host as {@code git clone --mirror} or {@code git init --bare} on the shared
   * volume". The test suite also uses it directly to provision a repository in whichever backend is
   * selected.
   *
   * @throws IOException if the repository already exists or cannot be created
   */
  void create(String repoId, String defaultBranch) throws IOException;

  /**
   * Every repository this backend currently holds, by id — the enumeration {@code GET
   * /artifacts/git} answers, and what qits-ci's trigger engine reads to know which repositories an
   * event-triggered pipeline could fire for.
   *
   * <p><b>Order is not part of this contract and the route does not trust it.</b> The wire answer is
   * sorted lexicographically and filtered to ids that are valid repo-id slugs, both in {@link
   * GitHostRoutes}: sorting is a property of the response, and the slug rule is a property of the
   * url, so neither belongs in a backend that could get it subtly wrong.
   *
   * <p>Unlike {@link #open}, this <b>throws rather than reads empty</b>. A backend that cannot be
   * enumerated is not a host with no repositories, and answering an empty list would leave the
   * trigger engine believing it had nothing to trigger — a failure with no symptom anywhere. An
   * empty list means exactly one thing: this host serves no repository yet.
   *
   * @throws IOException if the backend cannot be enumerated
   */
  List<String> repositoryIds() throws IOException;
}
