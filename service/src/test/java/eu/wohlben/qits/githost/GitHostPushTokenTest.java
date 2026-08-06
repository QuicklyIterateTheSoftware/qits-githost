package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import eu.wohlben.qits.githost.persistence.RepositoryProtectionStore;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The platform-wide switch turned ON with a push token configured — the deployment shape
 * qits-local-up.sh produces, and the only configuration in which {@code -o qits.token=<value>} can
 * succeed at all.
 *
 * <p>A separate class because a profile is a separate process configuration: the token's
 * configured / configured-empty / unset cases cannot coexist in one Quarkus instance, and the unset
 * case (the shipped default) is the one {@link GitHostTest} runs under.
 *
 * <p>Note what this class proves that the per-repo override cannot: the guard fires from the
 * PLATFORM property alone, against a repository with no protection row at all.
 */
@QuarkusTest
@TestProfile(GitHostPushTokenTest.ProtectionOnWithAToken.class)
public class GitHostPushTokenTest {

  static final String TOKEN = "a-configured-push-token";

  public static class ProtectionOnWithAToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.repositories.git.protect-default-branch", "true",
          "qits.repositories.git.push-token", TOKEN);
    }
  }

  @Inject GitRepositoryProvider repositories;

  @Inject RepositoryProtectionStore protections;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  private String seedOrigin() throws Exception {
    return GitHostFixture.seedOrigin(repositories, gitBase);
  }

  private String refSha(String repoId, String ref) throws Exception {
    return GitHostFixture.requireRemoteRefSha(gitBase, repoId, ref);
  }

  @Test
  public void theProtectionFiresFromThePlatformPropertyAlone() throws Exception {
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    GitHostFixture.commitFile(clone, "reflex.txt", "muscle memory\n", "reflex");
    String refusal = GitHostFixture.gitExpectingFailure(clone, "git", "push", "origin", "main");

    assertTrue(refusal.contains("protected ref refs/heads/main"), refusal);
    assertTrue(refusal.contains("/workspaces/api/workspaces/{id}/integrate"), refusal);
    assertEquals(before, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void aMatchingTokenAllowsEvenANonFastForward() throws Exception {
    // "Push anyway" is the whole point of the token: unlike the release door it is not bounded to a
    // fast-forward, because the dev-loop and bootstrap cases it exists for include the force push.
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.rewriteTip(clone, "rewritten by a token holder");
    String rewritten = GitHostFixture.head(clone);
    GitHostFixture.git(
        clone, "git", "push", "--force", "-o", "qits.token=" + TOKEN, "origin", "main");

    assertEquals(rewritten, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void aMatchingTokenAllowsADelete() throws Exception {
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.git(clone, "git", "push", "-o", "qits.token=" + TOKEN, "origin", ":main");

    assertNull(GitHostFixture.remoteRefSha(gitBase, repoId, "refs/heads/main"));
  }

  @Test
  public void aWrongTokenIsRefusedAndSaysSoWithoutEchoingAnything() throws Exception {
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    GitHostFixture.commitFile(clone, "wrong.txt", "not the token\n", "wrong");
    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "-o", "qits.token=not-the-token", "origin", "main");

    assertTrue(refusal.contains("does not match"), refusal);
    // The configured value is never echoed — not in the refusal a stranger reads, and not in the
    // INFO log line either.
    assertTrue(!refusal.contains(TOKEN), refusal);
    assertEquals(before, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void aRepositoryCanOptOutInItsProtectionRow() throws Exception {
    // The override in the direction the platform switch cannot express: protection is ON for
    // everything, and one repository is exempt. It used to be `[qits] protectDefaultBranch = false`
    // in the bare's own config; it is a row now, because a DFS-backed repository has no config file
    // and one question with two answer sources eventually gets two answers.
    String repoId = seedOrigin();
    protections.setProtectionOverride(repoId, false);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.commitFile(clone, "exempt.txt", "not guarded here\n", "exempt");
    String pushed = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    assertEquals(pushed, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void theReleaseOptionStillCarriesAFastForward() throws Exception {
    // The integrate flow's push, in the configuration it will actually run in: protection on
    // platform-wide, a token configured that the flow does not hold and does not need.
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.commitFile(clone, "release.txt", "2026.731.193059\n", "release");
    String released = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "-o", "qits.release", "origin", "main");

    assertEquals(released, refSha(repoId, "refs/heads/main"));
  }
}
