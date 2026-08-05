package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The backend this host has always had: a bare origin at {@code <data-dir>/<repoId>/origin}, the
 * same layout {@code RepositoryService} clones into and the same volume qits-projects and
 * qits-workspaces mount.
 *
 * <p>Selected by {@code qits.repositories.git.storage=file}, which is the shipped default. It is the
 * rollback for the whole storage unification: no bare is ever deleted, so flipping the property back
 * is a redeploy and nothing else.
 */
@ApplicationScoped
public class FileGitRepositoryProvider implements GitRepositoryProvider {

  private static final Logger LOG = Logger.getLogger(FileGitRepositoryProvider.class);

  static final String NAME = "file";

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Repository open(String repoId) {
    Path origin = origin(repoId);
    if (!Files.isDirectory(origin)) {
      return null;
    }
    try {
      return new FileRepositoryBuilder().setGitDir(origin.toFile()).setMustExist(true).build();
    } catch (Exception e) {
      // A directory that exists but will not open is a different situation from one that is absent,
      // and both answer 404 — so without this line the two are indistinguishable from outside. That
      // mattered: a native build failed here for every repository (JGit's resource bundles were not
      // in the image) and the only symptom was a 404 that looked like an unknown id. Debug rather
      // than warn, because a malformed directory under the data dir is a deployment fact, not an
      // error this process can act on.
      LOG.debugf(e, "git repository %s exists at %s but could not be opened", repoId, origin);
      return null;
    }
  }

  @Override
  public void create(String repoId, String defaultBranch) throws IOException {
    Path origin = origin(repoId);
    if (Files.exists(origin)) {
      throw new IOException("git repository " + repoId + " already exists at " + origin);
    }
    Files.createDirectories(origin.getParent());
    try (Repository repo = new FileRepositoryBuilder().setGitDir(origin.toFile()).build()) {
      repo.create(true);
      // JGit's create() points HEAD at its own default branch name, so the platform's `main` has to
      // be set explicitly — the protected ref is the bare's HEAD, and a repository that disagrees
      // with the platform about its name protects the wrong thing.
      RefUpdate.Result result =
          repo.updateRef(Constants.HEAD, true).link(Constants.R_HEADS + defaultBranch);
      if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED) {
        throw new IOException("could not point HEAD at " + defaultBranch + ": " + result.name());
      }
    }
  }

  /**
   * The data dir's immediate children that hold a bare — one directory read, no repository opened.
   *
   * <p>A child is a repository when it has an {@code origin} directory under it, which is the layout
   * {@link #origin} writes and the one qits-projects and qits-workspaces mount. Anything else on the
   * volume is simply not one, so nothing else is listed.
   *
   * <p>A data dir that does not exist yet is a host with no repositories, not a failure: that is how
   * a deployment reads before it is ever provisioned. Every other I/O failure propagates, per the
   * port — an unreadable directory must not read as an empty host.
   */
  @Override
  public List<String> repositoryIds() throws IOException {
    Path base = Path.of(dataDir);
    if (!Files.isDirectory(base)) {
      return List.of();
    }
    try (Stream<Path> children = Files.list(base)) {
      return children
          .filter(child -> Files.isDirectory(child.resolve("origin")))
          .map(child -> child.getFileName().toString())
          .toList();
    }
  }

  /** The id is validated as a slug before it reaches here, so this join cannot escape the dir. */
  private Path origin(String repoId) {
    return Path.of(dataDir, repoId, "origin");
  }
}
