package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-projects down: the push still succeeds and the CI event still leaves. The backup trigger is
 * fire-and-forget like everything else on this path, so an intake that refuses the connection costs
 * a missed backup — never a failed push, and never the other consumer's event.
 *
 * <p>Its own class because it needs its own two urls: ci at {@link StubIntake}, projects at a closed
 * port. That is one process configuration, so it is one class (see {@link GitHostNoCiOptionTest}).
 */
@QuarkusTest
@TestProfile(GitHostProjectsIntakeDownTest.ProjectsUnreachable.class)
public class GitHostProjectsIntakeDownTest {

  public static class ProjectsUnreachable implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.ci.intake-url", StubIntake.ciIntakeUrl(),
          "qits.projects.intake-url", "http://localhost:1/projects/api/events/post-receive");
    }
  }

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @BeforeEach
  void forgetPreviousDeliveries() {
    StubIntake.reset();
  }

  @Test
  public void anUnreachableProjectsIntakeNeitherFailsThePushNorStopsTheCiEvent() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    StubIntake.reset();

    GitHostFixture.commitFile(clone, "pushed.txt", "no backup listening\n", "pushed");
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    assertEquals(
        GitHostFixture.head(clone),
        GitHostFixture.requireRemoteRefSha(gitBase, repoId, "refs/heads/main"),
        "the ref lands whatever the backup trigger answers");
    assertTrue(
        StubIntake.awaitCiDelivery(), "and the CI event goes out independently of the other one");
    assertEquals(0, StubIntake.projectsDeliveries(), "nothing reached the closed port, by design");
  }
}
