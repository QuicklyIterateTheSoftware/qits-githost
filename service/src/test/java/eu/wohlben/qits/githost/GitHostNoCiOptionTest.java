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
 * {@code -o qits.no-ci} (workstream BN) over a real push: the option produces no delivery to the CI
 * intake, a push without it still does — the regression the option must never become — and the
 * qits-projects intake is told <b>either way</b>, because that event triggers the repository's
 * backup push and a backup is owed even for a push CI ignores.
 *
 * <p>A standalone class rather than more cases in {@link GitHostTest}, because the two intake urls
 * have to point at {@link StubIntake} for this and only this — the suite otherwise runs against
 * closed ports on purpose (see the shared test {@code application.properties}), and giving {@link
 * GitHostTest} a {@code @TestProfile} would contradict the shipped configuration its own javadoc
 * claims. Same shape as {@link GitHostPushTokenTest}/{@link GitHostEmptyPushTokenTest}: a process
 * configuration is a class, not a case.
 */
@QuarkusTest
@TestProfile(GitHostNoCiOptionTest.IntakesStubbed.class)
public class GitHostNoCiOptionTest {

  public static class IntakesStubbed implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.ci.intake-url", StubIntake.ciIntakeUrl(),
          "qits.projects.intake-url", StubIntake.projectsIntakeUrl());
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
  public void theNoCiOptionSuppressesTheIntakePost() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    StubIntake.reset(); // seedOrigin's own push is an ordinary one and already fired an event

    GitHostFixture.commitFile(
        clone, "imported.txt", "history that predates the platform\n", "import");
    GitHostFixture.git(clone, "git", "push", "-o", "qits.no-ci", "origin", "main");

    // The backup event is the one that must arrive, so wait for it rather than for a clock: by the
    // time it is here a CI event fired on the same push would be here too.
    assertTrue(
        StubIntake.awaitProjectsDelivery(),
        "a push carrying -o qits.no-ci must still be backed up");
    assertEquals(
        0, StubIntake.ciDeliveries(), "a push carrying -o qits.no-ci must fire no CI event");
  }

  @Test
  public void aPushWithoutTheOptionStillFiresTheIntakePost() throws Exception {
    String repoId = GitHostFixture.seedOrigin(repositories, gitBase);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    StubIntake.reset();

    GitHostFixture.commitFile(clone, "ordinary.txt", "an ordinary push\n", "ordinary");
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    assertTrue(StubIntake.awaitCiDelivery(), "an ordinary push must still fire a CI event");
    assertTrue(StubIntake.awaitProjectsDelivery(), "and the backup event beside it");
  }
}
