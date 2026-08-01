package eu.wohlben.qits.githost;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which {@link GitRepositoryProvider} this deployment serves from.
 *
 * <p>The {@code Instance<T>} pattern {@link RepositoryNameResolver} already uses, for a different
 * reason: there the question is whether a port is implemented at all, here it is which of two
 * implementations answers. Both are in the binary either way — the selection is <b>runtime</b>, so a
 * deployment flips it with an environment variable and a restart, and the rollback for the whole
 * storage unification is that flip plus a redeploy.
 *
 * <p>Ships {@code file}. The git host serves the push that redeploys the git host, so the storage
 * engine underneath it is the last thing that should change by default.
 *
 * <p>An unknown value <b>fails the boot</b> rather than falling back. A typo that silently kept the
 * old backend would look exactly like a successful cutover until someone went looking for the data.
 */
@ApplicationScoped
public class GitRepositoryBackend {

  private static final Logger LOG = Logger.getLogger(GitRepositoryBackend.class);

  /**
   * {@code file} — bare origins on the shared volume, the backend this host has always had — or
   * {@code dfs} — packs and refs as blobs in this service's own content-addressed store.
   */
  @ConfigProperty(name = "qits.repositories.git.storage", defaultValue = FileGitRepositoryProvider.NAME)
  String storage;

  @Inject Instance<GitRepositoryProvider> providers;

  private GitRepositoryProvider selected;

  @PostConstruct
  void select() {
    List<String> known = new ArrayList<>();
    for (GitRepositoryProvider provider : providers) {
      known.add(provider.name());
      if (provider.name().equals(storage)) {
        selected = provider;
      }
    }
    if (selected == null) {
      throw new IllegalStateException(
          "qits.repositories.git.storage=" + storage + " names no git storage backend; known: "
              + known);
    }
    LOG.infof("git host storage backend: %s", selected.name());
  }

  /** The provider this deployment serves from. Never null once the bean is constructed. */
  public GitRepositoryProvider provider() {
    return selected;
  }
}
