package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the in-process JGit smart-HTTP server ({@link GitHostRoutes} at {@code
 * /artifacts/git/*}) that workspace containers clone from and push to: a real {@code git clone} +
 * {@code push} round-trip moves the ref in the served bare origin, an unknown repo id is a 404, and
 * a traversal-shaped id is rejected. No docker is involved — this exercises only the git hosting.
 *
 * <p>The paths are spelled out absolutely on purpose. These routes are raw Vert.x and carry the
 * gateway segment as a literal, so nothing in the JAX-RS configuration would catch them drifting —
 * this suite is the only thing that does.
 *
 * <p>The monorepo seeded its bare origins from a {@code /fixtures/testing-repo.git} classpath
 * resource that an antrun step derived from a git submodule, and drove the name-addressed cases
 * through the projects/repositories import path. Neither exists here, so the origin is built
 * in-JVM by {@link #seedOrigin()} and the alias table is {@link FakeRepositoryNameResolver}.
 */
@QuarkusTest
public class GitHostTest {

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Inject FakeRepositoryNameResolver repositoryNames;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @BeforeEach
  void resetAliases() {
    repositoryNames.clear();
  }

  /**
   * Seeds a bare origin at {@code <data-dir>/<repoId>/origin} — one commit on the default branch,
   * built with the git CLI so the served repository is a real on-disk bare, exactly as {@code
   * RepositoryService} would have cloned it.
   */
  private String seedOrigin() throws Exception {
    return GitHostFixture.seedOrigin(dataDir);
  }

  @Test
  public void cloneAndPushMovesTheRefInTheOrigin() throws Exception {
    String repoId = seedOrigin();
    Path origin = Path.of(dataDir, repoId, "origin");
    Path clone = Files.createTempDirectory("qits-githost-clone");
    Files.delete(clone); // git clone wants to create the target itself

    // Clone over the served HTTP endpoint.
    runGit(null, "git", "clone", gitBase + "/" + repoId, clone.toString());
    assertTrue(Files.exists(clone.resolve(".git")), "clone should have produced a working copy");

    String branch = runGit(clone, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();

    // Commit and push anonymously.
    Files.writeString(clone.resolve("pushed.txt"), "from a container\n");
    runGit(clone, "git", "add", "pushed.txt");
    runGit(
        clone,
        "git",
        "-c",
        "user.email=qits@local",
        "-c",
        "user.name=qits",
        "commit",
        "-m",
        "push");
    String pushedSha = runGit(clone, "git", "rev-parse", "HEAD").trim();
    runGit(clone, "git", "push", "origin", branch);

    // The ref moved in the served bare origin.
    String originSha = runGit(origin, "git", "rev-parse", "refs/heads/" + branch).trim();
    assertEquals(pushedSha, originSha, "push should have advanced the origin's branch ref");
  }

  @Test
  public void aPushLargerThanTheBodyHandlerDefaultSucceeds() throws Exception {
    // Regression guard for a silent 10 MiB cap. BodyHandler.create() is not unlimited: vertx-web's
    // BodyHandlerImpl defaults bodyLimit to 10485760, so every push above that was 413'd — while
    // this service's config comment and the README both said the binding number was 64M. The routes
    // now set the limit explicitly (qits.repositories.git.max-pack-size).
    //
    // 12 MB of incompressible bytes: git deflates the pack, so a compressible file would ride under
    // the old cap and prove nothing. It also puts the push past http.postBuffer (1 MB by default),
    // which is what makes git send it chunked — the encoding where the global wire ceiling does not
    // apply and only the BodyHandler's own limit does.
    String repoId = seedOrigin();
    Path origin = Path.of(dataDir, repoId, "origin");
    Path clone = Files.createTempDirectory("qits-githost-big-clone");
    Files.delete(clone);
    runGit(null, "git", "clone", gitBase + "/" + repoId, clone.toString());

    byte[] incompressible = new byte[12 * 1024 * 1024];
    new Random(20260729L).nextBytes(incompressible);
    Files.write(clone.resolve("big.bin"), incompressible);

    String branch = runGit(clone, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    runGit(clone, "git", "add", "big.bin");
    runGit(
        clone, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-m", "big");
    String pushedSha = runGit(clone, "git", "rev-parse", "HEAD").trim();
    runGit(clone, "git", "push", "origin", branch);

    assertEquals(
        pushedSha,
        runGit(origin, "git", "rev-parse", "refs/heads/" + branch).trim(),
        "a pack larger than BodyHandler's 10 MiB default must reach the origin");
  }

  @Test
  public void infoRefsAdvertisesUploadPackForAKnownRepo() throws Exception {
    String repoId = seedOrigin();
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-upload-pack-advertisement"));
  }

  @Test
  public void missingServiceParamIs403ForAKnownRepo() throws Exception {
    // A known repo id with no ?service= (dumb-HTTP) is 403. The handler opens the repo eagerly, so
    // this path must still close it — regression guard for the try(repo)-wraps-the-403 fix.
    String repoId = seedOrigin();
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }

  @Test
  public void unknownRepoIdIs404() {
    given()
        .when()
        .get("/artifacts/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void traversalShapedIdIsRejected() {
    // A dotted name can't match the strict repo-id slug, so the resolver refuses it (404) rather
    // than letting it walk out of the data dir.
    given()
        .when()
        .get("/artifacts/git/foo.bar/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void nameAddressedCloneResolvesThroughTheAliasTable() throws Exception {
    // A repository import registers the repo's url-basename ("testing-repo") as a project-scoped
    // name alias, so it is servable at /artifacts/git/<projectId>/<name> as well as
    // /artifacts/git/<repoId>. The two schemes still differ in path length under the new prefix,
    // which is what keeps Vert.x dispatching them unambiguously.
    String projectId = UUID.randomUUID().toString();
    String repoId = seedOrigin();
    repositoryNames.register(projectId, "testing-repo", repoId);

    Path clone = Files.createTempDirectory("qits-githost-named-clone");
    Files.delete(clone);
    runGit(null, "git", "clone", gitBase + "/" + projectId + "/testing-repo", clone.toString());
    assertTrue(
        Files.exists(clone.resolve(".git")), "name-addressed clone should produce a working copy");

    // A trailing .git on the name segment is stripped before the alias lookup.
    Path cloneDotGit = Files.createTempDirectory("qits-githost-named-clone-dotgit");
    Files.delete(cloneDotGit);
    runGit(
        null,
        "git",
        "clone",
        gitBase + "/" + projectId + "/testing-repo.git",
        cloneDotGit.toString());
    assertTrue(
        Files.exists(cloneDotGit.resolve(".git")), "a .git suffix on the name still resolves");

    // The id-addressed route keeps working for the same repo (back-compat).
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  // --- the default branch's seatbelt (ProtectedRefHook) -------------------------------------------
  // These run under the SHIPPED configuration: qits.repositories.git.protect-default-branch=false
  // and no qits.repositories.git.push-token at all. So they cover the two halves this profile can
  // prove — that the feature is inert as shipped, and that a repository which opts IN through its
  // own bare config gets the whole refuse/accept matrix, with the token unconfigured (nothing
  // matches). The configured and configured-empty token cases are different process configurations
  // and live in GitHostPushTokenTest / GitHostEmptyPushTokenTest.

  @Test
  public void theReceivePackAdvertisementOffersPushOptions() throws Exception {
    // The silent-failure guard. A client only sends `-o` if the capability was advertised HERE, in
    // infoRefs — so a ReceivePack that allows push options on the POST but not on the GET produces
    // the confusing failure where every option is simply never seen and every bypass is refused.
    // The two setAllowPushOptions calls are one feature; this asserts the half that has no other
    // symptom.
    String repoId = seedOrigin();
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-receive-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-receive-pack-advertisement"))
        .body(containsString("push-options"));
  }

  @Test
  public void protectionIsInertInTheShippedDefaults() throws Exception {
    // The trap this workstream is built around: the change ships by a push to the very service that
    // would refuse it. Default-off is what makes that safe, and "off" has to mean off for the
    // roughest push there is — a force push over the default branch, with no option supplied.
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.rewriteTip(clone, "rewritten with no option at all");
    String rewritten = GitHostFixture.head(clone);
    runGit(clone, "git", "push", "--force", "origin", "main");

    assertEquals(
        rewritten,
        GitHostFixture.refSha(origin, "refs/heads/main"),
        "with protection off a plain force push must behave exactly as it always did");
  }

  @Test
  public void aDirectUpdateOfTheProtectedRefIsRefusedWithAnActionableMessage() throws Exception {
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = GitHostFixture.refSha(origin, "refs/heads/main");

    GitHostFixture.commitFile(clone, "direct.txt", "by hand\n", "direct");
    String refusal = GitHostFixture.gitExpectingFailure(clone, "git", "push", "origin", "main");

    assertTrue(refusal.contains("protected ref refs/heads/main"), refusal);
    // A refusal a human cannot act on is a worse bug than the accidental push, so both doors are
    // named: where releases go, and what the alternative requires.
    assertTrue(refusal.contains("/workspaces/api/workspaces/{id}/integrate"), refusal);
    assertTrue(refusal.contains("qits.token="), refusal);
    assertEquals(
        before,
        GitHostFixture.refSha(origin, "refs/heads/main"),
        "a refused push must leave the ref exactly where it was");
  }

  @Test
  public void theReleaseOptionAcceptsAFastForward() throws Exception {
    // The sanctioned domain door: what qits-workspaces' integrate flow sends, and nothing else.
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.commitFile(clone, "release.txt", "2026.731.193059\n", "release");
    String released = GitHostFixture.head(clone);
    runGit(clone, "git", "push", "-o", "qits.release", "origin", "main");

    assertEquals(released, GitHostFixture.refSha(origin, "refs/heads/main"));
  }

  @Test
  public void theReleaseOptionRefusesANonFastForward() throws Exception {
    // Fast-forward-only is what bounds the release door: the integrate flow's push IS the
    // compare-and-swap that makes two concurrent releases resolve into one, and granting it force
    // would defeat that silently.
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = GitHostFixture.refSha(origin, "refs/heads/main");

    GitHostFixture.rewriteTip(clone, "rewritten history");
    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "--force", "-o", "qits.release", "origin", "main");

    assertTrue(refusal.contains("fast-forward only"), refusal);
    assertEquals(before, GitHostFixture.refSha(origin, "refs/heads/main"));
  }

  @Test
  public void deletingTheProtectedRefIsRefusedWithAndWithoutTheReleaseOption() throws Exception {
    // The accidental `:main` is exactly what a seatbelt is for, and the release door never deletes.
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = GitHostFixture.refSha(origin, "refs/heads/main");

    String bare = GitHostFixture.gitExpectingFailure(clone, "git", "push", "origin", ":main");
    assertTrue(bare.contains("protected ref refs/heads/main"), bare);

    String withRelease =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "-o", "qits.release", "origin", ":main");
    assertTrue(withRelease.contains("never deletes it"), withRelease);

    assertEquals(before, GitHostFixture.refSha(origin, "refs/heads/main"), "main must still exist");
  }

  @Test
  public void creatingTheDefaultBranchIsAllowedWhileProtectionIsOn() throws Exception {
    // An empty repository has no default branch to protect, and blocking the seeding push buys no
    // safety — this is what keeps qits-local-up.sh's first-run push working with no option at all.
    String repoId = GitHostFixture.emptyOrigin(dataDir);
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);

    Path local = GitHostFixture.localRepo();
    String seeded = GitHostFixture.head(local);
    runGit(local, "git", "push", gitBase + "/" + repoId, "main");

    assertEquals(seeded, GitHostFixture.refSha(origin, "refs/heads/main"));
  }

  @Test
  public void anyOtherBranchIsUntouchedByProtection() throws Exception {
    // The protected ref is the bare's HEAD and nothing else. Workspace branches are force-pushed
    // and deleted constantly, and this guard must be invisible to them.
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    runGit(clone, "git", "switch", "-q", "-c", "workspace-branch");
    GitHostFixture.commitFile(clone, "work.txt", "in progress\n", "work");
    runGit(clone, "git", "push", "origin", "workspace-branch");
    GitHostFixture.rewriteTip(clone, "reworked");
    runGit(clone, "git", "push", "--force", "origin", "workspace-branch");
    assertEquals(
        GitHostFixture.head(clone), GitHostFixture.refSha(origin, "refs/heads/workspace-branch"));

    runGit(clone, "git", "push", "origin", ":workspace-branch");
    GitHostFixture.gitExpectingFailure(origin, "git", "rev-parse", "refs/heads/workspace-branch");
  }

  @Test
  public void aPushTokenIsRefusedWhenNoneIsConfigured() throws Exception {
    // Settled decision 3, and the half of it most easily got wrong: UNSET means nothing matches. A
    // deployment with protection on and no token configured has no escape hatch at all, and the
    // message says so rather than leaving the pusher guessing at a value.
    String repoId = seedOrigin();
    Path origin = GitHostFixture.origin(dataDir, repoId);
    GitHostFixture.protectDefaultBranch(origin, true);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = GitHostFixture.refSha(origin, "refs/heads/main");

    GitHostFixture.commitFile(clone, "guessed.txt", "let me in\n", "guess");
    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "-o", "qits.token=anything-at-all", "origin", "main");

    assertTrue(refusal.contains("no push token configured"), refusal);
    assertEquals(before, GitHostFixture.refSha(origin, "refs/heads/main"));
  }

  @Test
  public void unknownNameInProjectIs404() {
    String projectId = UUID.randomUUID().toString();
    given()
        .when()
        .get("/artifacts/git/" + projectId + "/no-such-name/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  private String runGit(Path cwd, String... command) throws Exception {
    return GitHostFixture.git(cwd, command);
  }
}
