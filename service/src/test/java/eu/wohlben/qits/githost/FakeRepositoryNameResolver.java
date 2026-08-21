package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test double for the {@link RepositoryNameResolver} port. In the monorepo the name-addressed
 * routes were exercised through the real projects/repositories import path, which registered a
 * repo's url-basename as a project-scoped alias; that context does not live here, so the alias
 * table is a map the test fills directly.
 *
 * <p>{@code @ApplicationScoped} rather than {@code @Mock}: the port is injected as an {@code
 * Instance<>}, and a plain bean is all it takes to be the one implementation. It <b>overrides</b>
 * the shipped {@link HttpRepositoryNameResolver}, which carries {@code @DefaultBean} — so on this
 * classpath there is exactly one bean, this one, and no test dials qits-projects. The HTTP adapter is
 * covered on its own by {@link HttpRepositoryNameResolverTest}, outside CDI.
 */
@ApplicationScoped
public class FakeRepositoryNameResolver implements RepositoryNameResolver {

  private final Map<String, String> aliases = new ConcurrentHashMap<>();

  private final AtomicBoolean unavailable = new AtomicBoolean();

  /** Registers {@code (projectId, name) -> repoId}, the way a repository import would. */
  public void register(String projectId, String name, String repoId) {
    aliases.put(key(projectId, name), repoId);
  }

  /**
   * Makes every lookup throw, the way an unreachable qits-projects does. The port's two answers are
   * "no such name" and "could not ask", and only a test that can produce the second proves the
   * routes tell them apart.
   */
  public void beUnavailable(boolean unavailable) {
    this.unavailable.set(unavailable);
  }

  public void clear() {
    aliases.clear();
    unavailable.set(false);
  }

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    if (unavailable.get()) {
      throw new Unavailable("the fake resolver is standing in for an unreachable qits-projects");
    }
    return Optional.ofNullable(aliases.get(key(projectId, name)));
  }

  private static String key(String projectId, String name) {
    return projectId + "/" + name;
  }
}
