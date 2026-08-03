package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.persistence.RepositoryProtectionStore;
import io.quarkus.test.common.http.TestHTTPResource;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The git host's behaviour, stated once and run against <b>whichever storage backend the subclass
 * selects</b>: a real {@code git clone} + {@code push} round-trip moves the ref in the served
 * repository, an unknown repo id is a 404, a traversal-shaped id is rejected, and the default
 * branch's seatbelt refuses and accepts exactly what it is supposed to.
 *
 * <p>Every subclass is a {@code @QuarkusTest} and the backend is a config value, so the two
 * concrete classes are two process configurations of the same suite:
 *
 * <ul>
 *   <li>{@link GitHostTest} — {@code file}, the shipped default: bare origins on the shared volume.
 *   <li>{@link GitHostDfsTest} — {@code dfs}: packs, pack indexes and refs as blobs in this
 *       service's own content-addressed store.
 * </ul>
 *
 * <p><b>Nothing below reads a directory</b>, and that is the whole lever. A repository is
 * provisioned through the selected {@link GitRepositoryProvider} and every fact about it is then
 * asked over the wire — {@code git ls-remote} rather than {@code git rev-parse} in a served bare.
 * The old suite read the bare on disk, which cannot be translated at all: a DFS-backed repository
 * has no directory for the git CLI to open, by design.
 *
 * <p>Protection is likewise turned on through {@link RepositoryProtectionStore} rather than by
 * writing {@code [qits] protectDefaultBranch} into a bare's config. That mechanism is gone for both
 * backends, because a {@code DfsRepository}'s config does not persist and one question with two
 * answer sources eventually gets two answers.
 *
 * <p>The paths are spelled out absolutely on purpose. These routes are raw Vert.x and carry the
 * gateway segment as a literal, so nothing in the JAX-RS configuration would catch them drifting —
 * this suite is the only thing that does.
 */
abstract class GitHostSuite {

  @Inject FakeRepositoryNameResolver repositoryNames;

  @Inject GitRepositoryBackend backend;

  @Inject RepositoryProtectionStore protections;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @BeforeEach
  void resetAliases() {
    repositoryNames.clear();
  }

  /** The backend this subclass runs against — asserted so a profile that fails to apply is loud. */
  abstract String expectedBackend();

  @Test
  public void theSuiteIsRunningAgainstTheBackendItClaims() {
    // A @TestProfile that does not apply is silent: the suite would simply run the shipped backend
    // twice and prove half of what it says it proves.
    assertEquals(expectedBackend(), backend.provider().name());
  }

  /** One commit on {@code main}, in whichever backend is selected. */
  private String seedOrigin() throws Exception {
    return GitHostFixture.seedOrigin(backend.provider(), gitBase);
  }

  private void protect(String repoId) {
    protections.setProtectionOverride(repoId, true);
  }

  private String refSha(String repoId, String ref) throws Exception {
    return GitHostFixture.requireRemoteRefSha(gitBase, repoId, ref);
  }

  @Test
  public void cloneAndPushMovesTheRefInTheOrigin() throws Exception {
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);
    assertTrue(Files.exists(clone.resolve(".git")), "clone should have produced a working copy");

    String branch = GitHostFixture.git(clone, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();

    GitHostFixture.commitFile(clone, "pushed.txt", "from a container\n", "push");
    String pushedSha = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "origin", branch);

    assertEquals(
        pushedSha,
        refSha(repoId, "refs/heads/" + branch),
        "push should have advanced the served repository's branch ref");
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
    Path clone = GitHostFixture.clone(gitBase, repoId);

    byte[] incompressible = new byte[12 * 1024 * 1024];
    new Random(20260729L).nextBytes(incompressible);
    Files.write(clone.resolve("big.bin"), incompressible);

    String branch = GitHostFixture.git(clone, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    GitHostFixture.git(clone, "git", "add", "big.bin");
    GitHostFixture.commitFile(clone, "big.txt", "beside the blob\n", "big");
    String pushedSha = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "origin", branch);

    assertEquals(
        pushedSha,
        refSha(repoId, "refs/heads/" + branch),
        "a pack larger than BodyHandler's 10 MiB default must reach the repository");
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
    // The backend answers existence: an absent directory on one, an empty ref database on the
    // other. Both have to reach the same 404, or a clone of a typo'd id would hand back an empty
    // repository and look like a repository that lost its history.
    given()
        .when()
        .get("/artifacts/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void traversalShapedIdIsRejected() {
    // A dotted name can't match the strict repo-id slug, so the route refuses it (404) before any
    // backend sees it. The check stays on the route for exactly that reason: it is a property of
    // the url, and the backend that would be hurt by dropping it is not the one selected here.
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
    GitHostFixture.git(
        null, "git", "clone", gitBase + "/" + projectId + "/testing-repo", clone.toString());
    assertTrue(
        Files.exists(clone.resolve(".git")), "name-addressed clone should produce a working copy");

    // A trailing .git on the name segment is stripped before the alias lookup.
    Path cloneDotGit = Files.createTempDirectory("qits-githost-named-clone-dotgit");
    Files.delete(cloneDotGit);
    GitHostFixture.git(
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

  @Test
  public void unknownNameInProjectIs404() {
    String projectId = UUID.randomUUID().toString();
    given()
        .when()
        .get("/artifacts/git/" + projectId + "/no-such-name/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  // --- the default branch's seatbelt (ProtectedRefHook) -------------------------------------------
  // These run under the SHIPPED configuration: qits.repositories.git.protect-default-branch=false
  // and no qits.repositories.git.push-token at all. So they cover the two halves this profile can
  // prove — that the feature is inert as shipped, and that a repository which opts IN through its
  // protection row gets the whole refuse/accept matrix, with the token unconfigured (nothing
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
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.rewriteTip(clone, "rewritten with no option at all");
    String rewritten = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "--force", "origin", "main");

    assertEquals(
        rewritten,
        refSha(repoId, "refs/heads/main"),
        "with protection off a plain force push must behave exactly as it always did");
  }

  @Test
  public void aDirectUpdateOfTheProtectedRefIsRefusedWithAnActionableMessage() throws Exception {
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    GitHostFixture.commitFile(clone, "direct.txt", "by hand\n", "direct");
    String refusal = GitHostFixture.gitExpectingFailure(clone, "git", "push", "origin", "main");

    assertTrue(refusal.contains("protected ref refs/heads/main"), refusal);
    // A refusal a human cannot act on is a worse bug than the accidental push, so both doors are
    // named: where releases go, and what the alternative requires.
    assertTrue(refusal.contains("/workspaces/api/workspaces/{id}/integrate"), refusal);
    assertTrue(refusal.contains("qits.token="), refusal);
    assertEquals(
        before,
        refSha(repoId, "refs/heads/main"),
        "a refused push must leave the ref exactly where it was");
  }

  @Test
  public void theReleaseOptionAcceptsAFastForward() throws Exception {
    // The sanctioned domain door: what qits-workspaces' integrate flow sends, and nothing else.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.commitFile(clone, "release.txt", "2026.731.193059\n", "release");
    String released = GitHostFixture.head(clone);
    GitHostFixture.git(clone, "git", "push", "-o", "qits.release", "origin", "main");

    assertEquals(released, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void theReleaseOptionRefusesANonFastForward() throws Exception {
    // Fast-forward-only is what bounds the release door: the integrate flow's push IS the
    // compare-and-swap that makes two concurrent releases resolve into one, and granting it force
    // would defeat that silently.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    GitHostFixture.rewriteTip(clone, "rewritten history");
    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "--force", "-o", "qits.release", "origin", "main");

    assertTrue(refusal.contains("fast-forward only"), refusal);
    assertEquals(before, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void deletingTheProtectedRefIsRefusedWithAndWithoutTheReleaseOption() throws Exception {
    // The accidental `:main` is exactly what a seatbelt is for, and the release door never deletes.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    String bare = GitHostFixture.gitExpectingFailure(clone, "git", "push", "origin", ":main");
    assertTrue(bare.contains("protected ref refs/heads/main"), bare);

    String withRelease =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "-o", "qits.release", "origin", ":main");
    assertTrue(withRelease.contains("never deletes it"), withRelease);

    assertEquals(before, refSha(repoId, "refs/heads/main"), "main must still exist");
  }

  @Test
  public void creatingTheDefaultBranchIsAllowedWhileProtectionIsOn() throws Exception {
    // An empty repository has no default branch to protect, and blocking the seeding push buys no
    // safety — this is what keeps qits-local-up.sh's first-run push working with no option at all.
    String repoId = GitHostFixture.emptyOrigin(backend.provider());
    protect(repoId);

    Path local = GitHostFixture.localRepo();
    String seeded = GitHostFixture.head(local);
    GitHostFixture.git(local, "git", "push", gitBase + "/" + repoId, "main");

    assertEquals(seeded, refSha(repoId, "refs/heads/main"));
  }

  @Test
  public void anyOtherBranchIsUntouchedByProtection() throws Exception {
    // The protected ref is the repository's HEAD and nothing else. Workspace branches are
    // force-pushed and deleted constantly, and this guard must be invisible to them.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.git(clone, "git", "switch", "-q", "-c", "workspace-branch");
    GitHostFixture.commitFile(clone, "work.txt", "in progress\n", "work");
    GitHostFixture.git(clone, "git", "push", "origin", "workspace-branch");
    GitHostFixture.rewriteTip(clone, "reworked");
    GitHostFixture.git(clone, "git", "push", "--force", "origin", "workspace-branch");
    assertEquals(GitHostFixture.head(clone), refSha(repoId, "refs/heads/workspace-branch"));

    GitHostFixture.git(clone, "git", "push", "origin", ":workspace-branch");
    assertNull(
        GitHostFixture.remoteRefSha(gitBase, repoId, "refs/heads/workspace-branch"),
        "an unprotected branch must still be deletable");
  }

  @Test
  public void aPushTokenIsRefusedWhenNoneIsConfigured() throws Exception {
    // Settled decision 3, and the half of it most easily got wrong: UNSET means nothing matches. A
    // deployment with protection on and no token configured has no escape hatch at all, and the
    // message says so rather than leaving the pusher guessing at a value.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    GitHostFixture.commitFile(clone, "guessed.txt", "let me in\n", "guess");
    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "-o", "qits.token=anything-at-all", "origin", "main");

    assertTrue(refusal.contains("no push token configured"), refusal);
    assertEquals(before, refSha(repoId, "refs/heads/main"));
  }

  // --- tags (the release flow's checkout target and its uniqueness guarantee) ---------------------
  // Nothing here had ever been measured: no test in this repo pushed a tag, so JGit's acceptance of
  // one was inferred from ReceivePack's defaults. These five cases are that measurement, and the
  // release-split design rests on them.

  @Test
  public void anAnnotatedTagPushIsAcceptedWhileTheDefaultBranchIsProtected() throws Exception {
    // ProtectedRefHook guards exactly one ref name — the repository's HEAD — so a tag is simply
    // another ref to it. Measured rather than reasoned: this is the answer that decides whether the
    // release can carry a tag at all.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    String tagObject = GitHostFixture.tagObject(clone, "v2026.801.63140", "release");
    GitHostFixture.git(clone, "git", "push", "origin", tagObject + ":refs/tags/v2026.801.63140");

    assertEquals(
        tagObject,
        refSha(repoId, "refs/tags/v2026.801.63140"),
        "an annotated tag must land with protection on and no push option at all");
    // The advertisement peels a ref that names a tag OBJECT and only such a ref, so a non-null
    // answer here is "the ref names the tag, not the commit — peeling is the consumer's job".
    assertNotNull(
        GitHostFixture.peeledRemoteRef(gitBase, repoId, "refs/tags/v2026.801.63140"),
        "the ref must name the tag object");
  }

  @Test
  public void onePushCarriesTheBranchAndTheTagAsOneReceivePack() throws Exception {
    // The shape the release flow uses: `git push -o qits.release origin HEAD:refs/heads/main
    // <tagobj>:refs/tags/<version>`. One push is one receive-pack, so both commands ride one
    // pre-receive and one post-receive — which is why the single push option authorises the branch
    // update while the tag rides along beside it.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    GitHostFixture.commitFile(clone, "release.txt", "2026.801.63140\n", "release");
    String released = GitHostFixture.head(clone);
    String tagObject = GitHostFixture.tagObject(clone, "v2026.801.63140", "release");

    String trace =
        GitHostFixture.gitTracingHttp(
            clone,
            "git",
            "push",
            "-o",
            "qits.release",
            "origin",
            "HEAD:refs/heads/main",
            tagObject + ":refs/tags/v2026.801.63140");

    assertEquals(
        1L,
        GitHostFixture.receivePackRequests(trace),
        "both refs must ride ONE receive-pack, or they are two pushes and two hooks");
    assertEquals(released, refSha(repoId, "refs/heads/main"));
    assertEquals(tagObject, refSha(repoId, "refs/tags/v2026.801.63140"));
  }

  @Test
  public void anAtomicPushIsSupportedAndARefusedBranchTakesTheTagWithIt() throws Exception {
    // The measurement qits-workspaces needs before it chooses between one atomic push and a
    // sequence, and the answer was not the one JGit's ref backend suggests: this host DOES
    // advertise `atomic`, so the capability is negotiated and both commands stand or fall together.
    // It is also the measurement that chose the DFS ref backend — JGit's other one does not
    // advertise `atomic` at all, and a push carrying the flag fails outright against it.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);
    String before = refSha(repoId, "refs/heads/main");

    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-receive-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(containsString("atomic"));

    // --force is what makes this a measurement of the HOST rather than of the client: without it
    // git refuses a non-fast-forward locally and never sends anything, so the server's atomic
    // handling is never reached. Forced, both commands go out and ProtectedRefHook refuses the
    // branch — the release door is fast-forward only.
    GitHostFixture.rewriteTip(clone, "rewritten history");
    String tagObject = GitHostFixture.tagObject(clone, "v1", "atomic probe");

    String atomic =
        GitHostFixture.gitExpectingFailure(
            clone,
            "git",
            "push",
            "--atomic",
            "--force",
            "-o",
            "qits.release",
            "origin",
            "HEAD:refs/heads/main",
            tagObject + ":refs/tags/v1");
    assertTrue(atomic.contains("fast-forward only"), atomic);
    assertEquals(before, refSha(repoId, "refs/heads/main"));
    assertNull(
        GitHostFixture.remoteRefSha(gitBase, repoId, "refs/tags/v1"),
        "an atomic push whose branch was refused must leave no tag behind");

    // And the contrast that makes the flag worth passing: the same push without --atomic applies
    // the tag anyway, leaving a tag for a release that never landed.
    String partial =
        GitHostFixture.gitExpectingFailure(
            clone,
            "git",
            "push",
            "--force",
            "-o",
            "qits.release",
            "origin",
            "HEAD:refs/heads/main",
            tagObject + ":refs/tags/v1");
    assertTrue(partial.contains("fast-forward only"), partial);
    assertEquals(before, refSha(repoId, "refs/heads/main"));
    assertEquals(
        tagObject,
        refSha(repoId, "refs/tags/v1"),
        "without --atomic a refused branch command does not roll the tag back");
  }

  @Test
  public void pushingATagThatAlreadyExistsIsRefusedUnlessItIsForced() throws Exception {
    // Settled decision 3: the tag IS the version-uniqueness guarantee, because a second release
    // stamped the same version cannot create the ref a second time. Where the refusal comes from is
    // worth knowing precisely — the git CLI refuses any non-forced update of an existing
    // refs/tags/* ref off the advertisement, while this host allows it under --force, exactly as
    // JGit's receive.denyNonFastForwards default says it should. So the guarantee holds for the
    // release flow (which never forces) and is a client-side one.
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);

    String first = GitHostFixture.tagObject(clone, "v1", "first release");
    GitHostFixture.git(clone, "git", "push", "origin", first + ":refs/tags/v1");

    GitHostFixture.commitFile(clone, "second.txt", "again\n", "second");
    String second = GitHostFixture.tagObject(clone, "v1", "second release, same version");
    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone, "git", "push", "origin", second + ":refs/tags/v1");

    assertTrue(refusal.contains("already exists"), refusal);
    assertEquals(
        first,
        refSha(repoId, "refs/tags/v1"),
        "the first release's tag must survive the second release's push");

    GitHostFixture.git(clone, "git", "push", "--force", "origin", second + ":refs/tags/v1");
    assertEquals(
        second,
        refSha(repoId, "refs/tags/v1"),
        "the host itself allows a forced tag move, so the release push must never force");
  }

  @Test
  public void aDuplicateTagDoesNotStopTheBranchHalfOfTheSamePush() throws Exception {
    // What a duplicate version costs WITHOUT --atomic, which is the case the release flow has to
    // choose against: the tag command is rejected and the branch command lands anyway, so "the tag
    // guarantees uniqueness" would mean the release fails with its merge commit already on main.
    // The push above shows --atomic is available and prevents exactly this.
    String repoId = seedOrigin();
    protect(repoId);
    Path clone = GitHostFixture.clone(gitBase, repoId);

    String first = GitHostFixture.tagObject(clone, "v1", "first release");
    GitHostFixture.git(clone, "git", "push", "origin", first + ":refs/tags/v1");

    GitHostFixture.commitFile(clone, "again.txt", "same version\n", "second release");
    String released = GitHostFixture.head(clone);
    String second = GitHostFixture.tagObject(clone, "v1", "second release, same version");

    String refusal =
        GitHostFixture.gitExpectingFailure(
            clone,
            "git",
            "push",
            "-o",
            "qits.release",
            "origin",
            "HEAD:refs/heads/main",
            second + ":refs/tags/v1");

    assertTrue(refusal.contains("already exists"), refusal);
    assertEquals(first, refSha(repoId, "refs/tags/v1"), "the tag did not move");
    assertEquals(
        released,
        refSha(repoId, "refs/heads/main"),
        "and main moved anyway — the two commands are independent");
  }

  // --- the lifecycle routes: PUT/GET/HEAD /artifacts/git/:repoId (workstream BM) -------------------

  @Test
  public void putCreatesANewRepositoryAnd201s() {
    String repoId = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode())
        .body("repoId", equalTo(repoId))
        .body("defaultBranch", equalTo("main"));
  }

  @Test
  public void aSecondPutOnTheSameIdIs200AndLeavesTheRepositoryUnchanged() {
    // PUT is idempotent: 201 on the call that created it, 200 on every call after — and a second
    // call asking for a DIFFERENT branch must not disturb the one already there.
    String repoId = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"other\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repoId", equalTo(repoId))
        .body("defaultBranch", equalTo("main"));
  }

  @Test
  public void putWithAnInvalidDefaultBranchIs400() {
    // The argv-safety check named in the plan: a leading dash reads as an option, not a branch.
    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"-oops\"}")
        .when()
        .put("/artifacts/git/" + UUID.randomUUID())
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void getReportsTheDefaultBranchOfAnExistingRepository() {
    String repoId = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());

    given()
        .when()
        .get("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repoId", equalTo(repoId))
        .body("defaultBranch", equalTo("main"));
  }

  @Test
  public void getOnAnUnknownIdIs404OnTheLifecycleRoute() {
    given()
        .when()
        .get("/artifacts/git/" + UUID.randomUUID())
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void aTraversalShapedIdOnTheLifecycleRoutesIs400() {
    // Malformed input, not "not found" — unlike the smart-HTTP routes, which stay 404 so a
    // traversal-shaped id looks identical to an unknown one from outside.
    given()
        .when()
        .get("/artifacts/git/foo.bar")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/foo.bar")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void headReportsExistenceWithNoBody() {
    String repoId = UUID.randomUUID().toString();
    given().when().head("/artifacts/git/" + repoId).then().statusCode(
        Response.Status.NOT_FOUND.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());

    given().when().head("/artifacts/git/" + repoId).then().statusCode(
        Response.Status.OK.getStatusCode());
  }

  @Test
  public void aPushToAPutCreatedRepositoryIsAccepted() throws Exception {
    String repoId = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());

    Path local = GitHostFixture.localRepo();
    String seeded = GitHostFixture.head(local);
    GitHostFixture.git(local, "git", "push", gitBase + "/" + repoId, "main");

    assertEquals(seeded, refSha(repoId, "refs/heads/main"));
  }
}
