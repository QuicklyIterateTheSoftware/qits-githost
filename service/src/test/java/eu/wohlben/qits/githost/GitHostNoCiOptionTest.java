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
 * {@code -o qits.no-ci} (workstream BN) against the {@code file} backend: a push carrying the
 * option produces no delivery to the CI intake, and a push without it still does — the regression
 * the option must never become. {@link GitHostNoCiOptionDfsTest} is the same suite against {@code
 * dfs}.
 *
 * <p>A pair of standalone classes rather than two more cases in {@link GitHostSuite}, because
 * {@code qits.ci.intake-url} has to point at {@link StubCiIntake} for this and only this — the
 * suite otherwise runs against a closed port on purpose (see the shared test {@code
 * application.properties}), and giving the SHIPPED-config class ({@link GitHostTest}) a {@code
 * @TestProfile} would contradict the one its own javadoc states. Same shape as {@link
 * GitHostPushTokenTest}/{@link GitHostEmptyPushTokenTest}: a process configuration is a class, not a
 * case.
 */
@QuarkusTest
@TestProfile(GitHostNoCiOptionTest.IntakeStubbed.class)
public class GitHostNoCiOptionTest {

  public static class IntakeStubbed implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.ci.intake-url", StubCiIntake.intakeUrl());
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
