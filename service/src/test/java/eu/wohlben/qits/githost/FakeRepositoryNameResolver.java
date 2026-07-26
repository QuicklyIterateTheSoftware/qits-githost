package eu.wohlben.qits.githost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for the {@link RepositoryNameResolver} port. In the monorepo the name-addressed
 * routes were exercised through the real projects/repositories import path, which registered a
 * repo's url-basename as a project-scoped alias; that context does not live here, so the alias
 * table is a map the test fills directly.
 *
 * <p>{@code @ApplicationScoped} rather than {@code @Mock}: the port is injected as an {@code
 * Instance<>}, and a plain bean in test sources is exactly the "an implementation is present"
 * configuration production runs in.
 */
@ApplicationScoped
public class FakeRepositoryNameResolver implements RepositoryNameResolver {

  private final Map<String, String> aliases = new ConcurrentHashMap<>();

  /** Registers {@code (projectId, name) -> repoId}, the way a repository import would. */
  public void register(String projectId, String name, String repoId) {
    aliases.put(key(projectId, name), repoId);
  }

  public void clear() {
    aliases.clear();
  }

  @Override
  public Optional<String> resolveRepositoryId(String projectId, String name) {
    return Optional.ofNullable(aliases.get(key(projectId, name)));
  }

  private static String key(String projectId, String name) {
    return projectId + "/" + name;
  }
}
