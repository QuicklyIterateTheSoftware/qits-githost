package eu.wohlben.qits.githost;

import io.quarkus.test.junit.QuarkusTest;

/**
 * {@link GitHostSuite} against the <b>shipped</b> configuration: bare origins on the shared volume,
 * protection off platform-wide, no push token.
 *
 * <p>No {@code @TestProfile} at all, and that is the point — this class is what the deployed process
 * does. {@link GitHostDfsTest} is the same suite with one config value changed.
 *
 * <p>The monorepo seeded its bare origins from a {@code /fixtures/testing-repo.git} classpath
 * resource that an antrun step derived from a git submodule, and drove the name-addressed cases
 * through the projects/repositories import path. Neither exists here, so a repository is provisioned
 * through the storage backend itself and the alias table is {@link FakeRepositoryNameResolver}.
 */
@QuarkusTest
public class GitHostTest extends GitHostSuite {

  @Override
  String expectedBackend() {
    return "file";
  }
}
