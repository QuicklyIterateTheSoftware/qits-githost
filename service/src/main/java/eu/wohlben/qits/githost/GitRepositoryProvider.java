package eu.wohlben.qits.githost;

import java.io.IOException;
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
   * <p>No route calls this. Creation reaches this host as {@code git clone --mirror} or {@code git
   * init --bare} on the shared volume today, which is exactly the filesystem coupling workstream AT
   * removes; when it does, its new create verb lands on this method rather than beside it. It exists
   * now because the test suite needs one way to provision a repository in whichever backend is
   * selected.
   *
   * @throws IOException if the repository already exists or cannot be created
   */
  void create(String repoId, String defaultBranch) throws IOException;
}
