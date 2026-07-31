package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Protection on with the push token configured <b>empty</b> — the one case where a plausible
 * implementation is wrong in the dangerous direction.
 *
 * <p>An empty configured token must match <b>nothing</b>, and in particular must never satisfy an
 * empty presented value ({@code -o qits.token=}). "Empty allows empty" is what a naive
 * {@code configured.equals(presented)} does, and it would turn a deployment that set the variable to
 * nothing — an env file with a blank line, an unresolved substitution — into a deployment with no
 * protection at all and no way to tell.
 */
@QuarkusTest
@TestProfile(GitHostEmptyPushTokenTest.ProtectionOnWithAnEmptyToken.class)
public class GitHostEmptyPushTokenTest {

  public static class ProtectionOnWithAnEmptyToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.repositories.git.protect-default-branch", "true",
          "qits.repositories.git.push-token", "");
    }
  }

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @Test
  public void anEmptyConfiguredTokenIsSatisfiedByNothingAtAll() throws Exception {
    String repoId = GitHostFixture.seedOrigin(dataDir);
    Path origin = GitHostFixture.origin(dataDir, repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = GitHostFixture.refSha(origin, "refs/heads/main");

    GitHostFixture.commitFile(clone, "empty.txt", "nothing to present\n", "empty");

    // The empty value, which is the case this class exists for.
    String presentedEmpty =
        GitHostFixture.gitExpectingFailure(clone, "git", "push", "-o", "qits.token=", "origin",
            "main");
    assertTrue(presentedEmpty.contains("no push token configured"), presentedEmpty);

    // And any other value, since there is nothing here for it to equal.
    String presentedSomething =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "-o", "qits.token=something", "origin", "main");
    assertTrue(presentedSomething.contains("no push token configured"), presentedSomething);

    assertEquals(before, GitHostFixture.refSha(origin, "refs/heads/main"));
  }
}
