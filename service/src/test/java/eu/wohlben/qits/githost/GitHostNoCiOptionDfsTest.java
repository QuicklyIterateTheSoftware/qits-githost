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
 * {@link GitHostNoCiOptionTest} against the {@code dfs} backend — the same two facts (the option
 * suppresses delivery, its absence does not) measured against the storage engine with no directory
 * anywhere, so post-receive notification is proved backend-agnostic the same way {@link
 * GitHostDfsTest} proves the rest of the routes are.
 */
@QuarkusTest
@TestProfile(GitHostNoCiOptionDfsTest.IntakeStubbedOnDfs.class)
public class GitHostNoCiOptionDfsTest {

  public static class IntakeStubbedOnDfs implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.ci.intake-url", StubCiIntake.intakeUrl(),
          "qits.repositories.git.storage", "dfs");
    }
  }

  @Inject GitRepositoryBackend backend;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @BeforeEach
  void forgetPreviousDeliveries() {
    StubCiIntake.reset();
  }

  @Test
  public void theSuiteIsRunningAgainstTheDfsBackend() {
    // A @TestProfile that failed to apply would silently run this suite against file a second
    // time, proving nothing about dfs — the same guard GitHostDfsTest carries.
    assertEquals("dfs", backend.provider().name());
  }

  @Test
  public void theNoCiOptionSuppressesTheIntakePost() throws Exception {
    String repoId = GitHostFixture.seedOrigin(backend.provider(), gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    StubCiIntake.reset(); // seedOrigin's own push is an ordinary one and already fired an event

    GitHostFixture.commitFile(
        clone, "imported.txt", "history that predates the platform\n", "import");
    GitHostFixture.git(clone, "git", "push", "-o", "qits.no-ci", "origin", "main");

    Thread.sleep(300); // the notifier is fire-and-forget; give a spurious delivery time to arrive
    assertEquals(
        0, StubCiIntake.deliveries(), "a push carrying -o qits.no-ci must fire no CI event");
  }

  @Test
  public void aPushWithoutTheOptionStillFiresTheIntakePost() throws Exception {
    String repoId = GitHostFixture.seedOrigin(backend.provider(), gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    StubCiIntake.reset();

    GitHostFixture.commitFile(clone, "ordinary.txt", "an ordinary push\n", "ordinary");
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    assertTrue(
        CiPostReceiveNotifierTest.awaitDelivery(), "an ordinary push must still fire a CI event");
  }
}
