package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static eu.wohlben.qits.PackagedProcessIT.runGit;
import static eu.wohlben.qits.PackagedProcessIT.runGitExpectingFailure;
import static eu.wohlben.qits.PackagedProcessIT.seedOrigin;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default branch's seatbelt, in the <b>binary</b>, with protection on platform-wide.
 *
 * <p>A second class and therefore a second launch of the same artifact, because this is a different
 * process configuration and {@code @TestProfile} is per class — the same reason the JVM suite splits
 * the push-token cases. {@link PackagedProcessIT} asserts the opposite half against the SHIPPED
 * defaults ({@code theShippedDefaultsLeaveTheDefaultBranchUnprotected}), and the two cannot coexist
 * in one process.
 *
 * <p>The per-repository override used to be {@code [qits] protectDefaultBranch} in the bare's own
 * config, which is what these cases turned on before — one {@code git config} on a directory the
 * test could reach. It is a row now (a DFS-backed repository has no config file), and a packaged
 * process owns its H2 exclusively with {@code clean-at-start}, so a test outside it cannot write
 * that row: there is no HTTP verb for it yet. Turning the PLATFORM switch on instead proves exactly
 * what this class is the gate for — the advertisement, the hook and the push options surviving the
 * native compile — and leaves the row's read to the JVM suite, where it is a plain Panache lookup
 * like every other table in this service. A create/protect verb is workstream AT's.
 */
@QuarkusIntegrationTest
@TestProfile(ProtectedGitHostIT.ProtectionOn.class)
class ProtectedGitHostIT {

  /**
   * The packaged IT's own on-disk state, plus the platform switch. It cannot extend {@code
   * TargetDirState} and add a key — a profile's overrides are one map — so the four paths are taken
   * from it and one more is put on top, which keeps the two classes writing to the same target
   * directories.
   */
  public static class ProtectionOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides =
          new LinkedHashMap<>(new PackagedProcessIT.TargetDirState().getConfigOverrides());
      overrides.put("qits.repositories.git.protect-default-branch", "true");
      return overrides;
    }
  }

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @Test
  void theProtectedRefGuardAndItsPushOptionsSurviveTheCompile() throws Exception {
    // Everything JGit-adjacent in this feature, in the binary — which is the gate here, because
    // this host has regressed natively before with a blanket 404 and a green JVM suite. Three
    // things can only be proved by a real client against a real process:
    //   * the capability is ADVERTISED, without which git never sends an option at all;
    //   * the pre-receive hook runs at all;
    //   * the options are parsed off the wire and reach the hook.
    String repoId = seedOrigin();
    Path origin =
        PackagedProcessIT.TargetDirState.ROOT.resolve("repositories").resolve(repoId).resolve("origin");

    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-receive-pack")
        .then()
        .statusCode(200)
        .body(containsString("push-options"));

    Path clone = Files.createTempDirectory("qits-artifacts-it-protected");
    Files.delete(clone);
    runGit(null, "git", "clone", "-q", gitBase + "/" + repoId, clone.toString());
    String before = runGit(origin, "git", "rev-parse", "refs/heads/main").trim();

    Files.writeString(clone.resolve("direct.txt"), "by hand\n");
    runGit(clone, "git", "add", "direct.txt");
    runGit(clone, "git", "-c", "user.email=q@l", "-c", "user.name=q", "commit", "-q", "-m", "d");

    String refused = runGitExpectingFailure(clone, "git", "push", "origin", "main");
    assertTrue(refused.contains("protected ref refs/heads/main"), refused);
    assertTrue(refused.contains("/workspaces/api/workspaces/{id}/integrate"), refused);
    assertEquals(
        before,
        runGit(origin, "git", "rev-parse", "refs/heads/main").trim(),
        "a refused push must leave the ref where it was");

    // No push token is configured in this process, and unset matches nothing — the default-locked
    // half of settled decision 3, proved against the shipped defaults rather than assumed.
    String refusedToken =
        runGitExpectingFailure(clone, "git", "push", "-o", "qits.token=guess", "origin", "main");
    assertTrue(refusedToken.contains("no push token configured"), refusedToken);

    // And the sanctioned door, which the integrate flow will use: fast-forward, accepted.
    String released = runGit(clone, "git", "rev-parse", "HEAD").trim();
    runGit(clone, "git", "push", "-o", "qits.release", "origin", "main");
    assertEquals(released, runGit(origin, "git", "rev-parse", "refs/heads/main").trim());
  }

  @Test
  void aReleasePushCarriesItsTagThroughTheBinary() throws Exception {
    // The release flow's whole push, in the binary: one `git push` with two refspecs — the branch
    // through the protected-ref door and an annotated tag beside it. JGit-adjacent and therefore
    // native-gated, because ReceivePack's ref advertisement, its capability negotiation and its
    // batch ref update are all machinery the JVM suite proves nothing about.
    String repoId = seedOrigin();
    Path origin =
        PackagedProcessIT.TargetDirState.ROOT.resolve("repositories").resolve(repoId).resolve("origin");

    // `atomic` is the capability qits-workspaces' push depends on to keep a tag from outliving a
    // refused release, and a client only sends it if it was offered here.
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-receive-pack")
        .then()
        .statusCode(200)
        .body(containsString("atomic"));

    Path clone = Files.createTempDirectory("qits-artifacts-it-tag");
    Files.delete(clone);
    runGit(null, "git", "clone", "-q", gitBase + "/" + repoId, clone.toString());

    Files.writeString(clone.resolve("VERSION"), "2026.801.63140\n");
    runGit(clone, "git", "add", "VERSION");
    runGit(clone, "git", "-c", "user.email=q@l", "-c", "user.name=q", "commit", "-q", "-m", "rel");
    String released = runGit(clone, "git", "rev-parse", "HEAD").trim();

    // tag -a, capture the object, tag -d: the tag has to travel as an object in the push, because
    // the release flow builds it in a worktree that shares the served bare's ref store.
    runGit(clone, "git", "-c", "user.email=q@l", "-c", "user.name=q", "tag", "-a", "v2026.801.63140",
        "-m", "release");
    String tagObject = runGit(clone, "git", "rev-parse", "v2026.801.63140").trim();
    runGit(clone, "git", "tag", "-d", "v2026.801.63140");

    runGit(clone, "git", "push", "--atomic", "-o", "qits.release", "origin",
        "HEAD:refs/heads/main", tagObject + ":refs/tags/v2026.801.63140");

    assertEquals(released, runGit(origin, "git", "rev-parse", "refs/heads/main").trim());
    assertEquals(
        tagObject,
        runGit(origin, "git", "rev-parse", "refs/tags/v2026.801.63140").trim(),
        "the tag must land in the same push as the branch");
    assertEquals(
        "tag",
        runGit(origin, "git", "cat-file", "-t", tagObject).trim(),
        "the ref names the tag OBJECT — peeling is the consumer's job");

    // And the uniqueness guarantee the release flow leans on: a second release stamped the same
    // version builds a different tag object, and the ref cannot be created twice.
    runGit(clone, "git", "-c", "user.email=q@l", "-c", "user.name=q", "tag", "-a",
        "v2026.801.63140", "-m", "second release, same version");
    String second = runGit(clone, "git", "rev-parse", "v2026.801.63140").trim();
    runGit(clone, "git", "tag", "-d", "v2026.801.63140");
    String duplicate =
        runGitExpectingFailure(clone, "git", "push", "origin", second + ":refs/tags/v2026.801.63140");
    assertTrue(duplicate.contains("already exists"), duplicate);
    assertEquals(tagObject, runGit(origin, "git", "rev-parse", "refs/tags/v2026.801.63140").trim());
  }

}
