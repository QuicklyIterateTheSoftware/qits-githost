package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.persistence.RepositoryProtectionStore;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The git host under the <b>shipped</b> configuration: protection off platform-wide, no push token.
 * A real {@code git clone} + {@code push} round-trip moves the ref in the served repository, an
 * unknown repo id is a 404, a traversal-shaped id is rejected, and the default branch's seatbelt
 * refuses and accepts exactly what it is supposed to.
 *
 * <p>No {@code @TestProfile} at all, and that is the point — this class is what the deployed process
 * does. It used to be an abstract suite with two subclasses, one per storage backend; there is one
 * backend now, so the suite and its shipped-config subclass are one class again.
 *
 * <p><b>Nothing below reads a directory</b>, and that constraint outlived the second backend. A
 * repository is provisioned through {@link GitRepositoryProvider} and every fact about it is then
 * asked over the wire — {@code git ls-remote} rather than {@code git rev-parse} in a served bare.
 * There is no bare to read: packs and refs are blobs, so the git CLI has nothing to open.
 *
 * <p>Protection is likewise turned on through {@link RepositoryProtectionStore} rather than by
 * writing {@code [qits] protectDefaultBranch} into a repository's config, because a {@code
 * DfsRepository}'s config does not persist.
 *
 * <p>The paths are spelled out absolutely on purpose. These routes are raw Vert.x and carry the
 * gateway segment as a literal, so nothing in the JAX-RS configuration would catch them drifting —
 * this suite is the only thing that does.
 */
@QuarkusTest
public class GitHostTest {

  @Inject FakeRepositoryNameResolver repositoryNames;

  @Inject GitRepositoryProvider repositories;

  @Inject RepositoryProtectionStore protections;

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @BeforeEach
  void resetAliases() {
    repositoryNames.clear();
  }

  /** One commit on {@code main}, in a repository this host provisioned itself. */
  private String seedOrigin() throws Exception {
    return GitHostFixture.seedOrigin(repositories, gitBase);
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
    // Existence is the ref database's answer: an id nothing was ever created under reads empty,
    // and that has to be a 404 — a clone of a typo'd id would otherwise hand back an empty
    // repository and look like a repository that lost its history.
    given()
        .when()
        .get("/artifacts/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void traversalShapedIdIsRejected() {
    // A dotted name can't match the strict repo-id slug, so the route refuses it (404) before the
    // provider sees it. The check stays on the route because it is a property of the url: nothing
    // under it touches a filesystem, so a traversal-shaped id would merely be an unknown id there.
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

  // --- content reads: blob and tree --------------------------------------------------------------
  // The verb the wire protocol has not got. Every case here is asked over HTTP against a repository
  // seeded through a real push, because what is under test is the route grammar and the resolution,
  // both of which a JGit call in-process would step straight over.

  /** The seeded repository plus a nested directory, so a tree listing has both kinds of entry. */
  private String contentOrigin() throws Exception {
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);
    Files.createDirectories(clone.resolve("docs"));
    Files.writeString(clone.resolve("docs/guide.md"), "guide\n");
    GitHostFixture.git(clone, "git", "add", "docs/guide.md");
    GitHostFixture.commitFile(clone, "pipeline.yml", "steps: []\n", "content");
    GitHostFixture.git(clone, "git", "push", "origin", "main");
    return repoId;
  }

  @Test
  public void aBlobIsServedBySpaAndByBranchNameWithTheResolvedCommitOnTheResponse()
      throws Exception {
    String repoId = contentOrigin();
    String head = refSha(repoId, "refs/heads/main");

    // By branch name: the answer names the commit it resolved to, which is what lets a caller pin a
    // later read to that sha instead of to a ref that may have moved.
    byte[] byBranch =
        given()
            .when()
            .get("/artifacts/git/" + repoId + "/blob/main/pipeline.yml")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(containsString("application/octet-stream"))
            .header("Git-Commit-Sha", equalTo(head))
            .extract()
            .asByteArray();
    assertEquals("steps: []\n", new String(byBranch, StandardCharsets.UTF_8));

    // By sha, at a path in a subdirectory. Reading at the exact sha is the point of the route: a
    // post-receive consumer holds one and must not race the branch.
    byte[] bySha =
        given()
            .when()
            .get("/artifacts/git/" + repoId + "/blob/" + head + "/docs/guide.md")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .header("Git-Commit-Sha", equalTo(head))
            .extract()
            .asByteArray();
    assertEquals("guide\n", new String(bySha, StandardCharsets.UTF_8));
  }

  @Test
  public void aBlobIsAlsoServedAtACommitNoRefPointsAt() throws Exception {
    // Reachable but not a tip: the shape a consumer reading a pushed sha is in once another push
    // has landed. UploadPack's want policy refuses this — deliberately, since a want costs a
    // reachability walk — so a content read that could not do it would send the caller back to
    // cloning.
    String repoId = contentOrigin();
    String parent = refSha(repoId, "refs/heads/main");
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.commitFile(clone, "later.txt", "after\n", "later");
    GitHostFixture.git(clone, "git", "push", "origin", "main");
    assertEquals(GitHostFixture.head(clone), refSha(repoId, "refs/heads/main"));

    given()
        .when()
        .get("/artifacts/git/" + repoId + "/blob/" + parent + "/pipeline.yml")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .header("Git-Commit-Sha", equalTo(parent));
  }

  @Test
  public void theTreeListsTheRootAndASubdirectory() throws Exception {
    String repoId = contentOrigin();
    String head = refSha(repoId, "refs/heads/main");

    given()
        .when()
        .get("/artifacts/git/" + repoId + "/tree/main")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("application/json"))
        .header("Git-Commit-Sha", equalTo(head))
        .body("entries.name", hasItems("README.md", "docs", "pipeline.yml"))
        .body("entries.find { it.name == 'docs' }.type", equalTo("tree"))
        .body("entries.find { it.name == 'README.md' }.type", equalTo("blob"));

    // A path names the directory to list, and a trailing slash is the same request.
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/tree/" + head + "/docs/")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.size()", equalTo(1))
        .body("entries[0].name", equalTo("guide.md"))
        .body("entries[0].type", equalTo("blob"));
  }

  @Test
  public void aSlashyBranchNameIsReadableAsAnEncodedSegment() throws Exception {
    // A rev is one path segment, so `feature/x` has to arrive as %2F — the npm scoped-package
    // lesson, and RestAssured re-encodes the escape unless url encoding is turned off.
    String repoId = contentOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);
    GitHostFixture.git(clone, "git", "switch", "-q", "-c", "feature/reads");
    GitHostFixture.commitFile(clone, "branchy.txt", "on a slashy branch\n", "branchy");
    GitHostFixture.git(clone, "git", "push", "origin", "feature/reads");

    byte[] body =
        given()
            .urlEncodingEnabled(false)
            .when()
            .get("/artifacts/git/" + repoId + "/blob/feature%2Freads/branchy.txt")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .header("Git-Commit-Sha", equalTo(GitHostFixture.head(clone)))
            .extract()
            .asByteArray();
    assertEquals("on a slashy branch\n", new String(body, StandardCharsets.UTF_8));
  }

  @Test
  public void everythingThatDoesNotResolveIs404() throws Exception {
    String repoId = contentOrigin();
    String head = refSha(repoId, "refs/heads/main");

    // A repository this host does not hold.
    given()
        .when()
        .get("/artifacts/git/" + UUID.randomUUID() + "/blob/main/pipeline.yml")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    // A ref that is not there.
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/blob/no-such-branch/pipeline.yml")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    // A well-formed sha nothing in this repository is. Repository.resolve hands a full id back
    // WITHOUT checking it exists, so this case is caught by parseCommit and nowhere else.
    given()
        .when()
        .get(
            "/artifacts/git/"
                + repoId
                + "/blob/0123456789abcdef0123456789abcdef01234567/pipeline.yml")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    // A path that is not in that commit.
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/blob/" + head + "/nowhere.yml")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    // A directory read as a blob, and a file listed as a tree: each resolves, and neither is what
    // the caller asked for.
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/blob/main/docs")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/tree/main/pipeline.yml")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/tree/main/no-such-directory")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void aMalformedRevIs400RatherThan404() throws Exception {
    // The MavenPaths lesson: an unusable request that missed the route is reported as an absent
    // resource and sends the caller debugging the wrong thing. So the rev group is loose in the
    // route and strict in the handler.
    String repoId = contentOrigin();
    // The last two are `main^{tree}` and `HEAD@{2}` — revision EXPRESSIONS, which Repository.resolve
    // would answer and this route will not. They are spelled percent-encoded because `^`, `@` and
    // the braces are not legal raw in a URI path, and decoding them before the check is exactly
    // what stops the escape being the way past it.
    for (String rev : new String[] {"-oops", "a..b", "main%5E%7Btree%7D", "HEAD%40%7B2%7D"}) {
      given()
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/git/" + repoId + "/blob/" + rev + "/pipeline.yml")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
      given()
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/git/" + repoId + "/tree/" + rev)
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @Test
  public void aBlobLargerThanTheRouteServesIs413() throws Exception {
    // The bound is a constant sized for source files. 9 MB of incompressible bytes clears it; a
    // compressible file would ride under it in the pack and prove nothing about what is served.
    String repoId = seedOrigin();
    Path clone = GitHostFixture.clone(gitBase, repoId);
    byte[] incompressible = new byte[9 * 1024 * 1024];
    new Random(20260806L).nextBytes(incompressible);
    Files.write(clone.resolve("big.bin"), incompressible);
    GitHostFixture.git(clone, "git", "add", "big.bin");
    GitHostFixture.commitFile(clone, "small.txt", "beside it\n", "big");
    GitHostFixture.git(clone, "git", "push", "origin", "main");

    given()
        .when()
        .get("/artifacts/git/" + repoId + "/blob/main/big.bin")
        .then()
        .statusCode(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
    // The bound is per blob, not per repository: the small file beside it is still served.
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/blob/main/small.txt")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void aRepositoryCalledBlobIsStillClonableByName() throws Exception {
    // The one request shape the content routes and the name-addressed scheme both match:
    // /artifacts/git/<projectId>/blob/info/refs is a clone of a repository CALLED blob, and it is
    // registered first. The handler hands it back to the router rather than answering it, so a
    // repository does not become unclonable because of what it is called.
    String projectId = UUID.randomUUID().toString();
    String repoId = seedOrigin();
    repositoryNames.register(projectId, "blob", repoId);

    Path clone = Files.createTempDirectory("qits-githost-blob-named-clone");
    Files.delete(clone);
    GitHostFixture.git(null, "git", "clone", "-q", gitBase + "/" + projectId + "/blob",
        clone.toString());
    assertTrue(Files.exists(clone.resolve(".git")), "a repository named blob must still clone");
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
    String repoId = GitHostFixture.emptyOrigin(repositories);
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

  // --- the collection listing: GET /artifacts/git ------------------------------------------------
  // A host is never empty by the time these run — the suite has created repositories above, and the
  // pack catalog is one H2 instance for the whole run — so every case here is stated as a property
  // of the whole answer rather than as an equality against one. The empty host is a process configuration
  // of its own: GitHostListingEmptyTest.

  /** {@code GET /artifacts/git}, as the ordered list of ids it carries. */
  private List<String> listRepositories() {
    return given()
        .when()
        .get("/artifacts/git")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("application/json"))
        .extract()
        .jsonPath()
        .getList("repositories", String.class);
  }

  @Test
  public void aCreatedRepositoryAppearsInTheListing() {
    String repoId = UUID.randomUUID().toString();
    assertFalse(listRepositories().contains(repoId), "an id nothing created must not be listed");

    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());

    assertTrue(
        listRepositories().contains(repoId),
        "a repository the host holds must be one the host lists");
  }

  @Test
  public void theListingIsSortedLexicographically() throws Exception {
    // Created out of order, under one run-unique prefix so the assertion survives a catalog that
    // outlives the class: the three are compared in isolation, and the whole answer is then checked
    // for sortedness so the order is a property of the response rather than of the insert order.
    String prefix = "listing-" + UUID.randomUUID();
    for (String suffix : new String[] {"-c", "-a", "-b"}) {
      repositories.create(prefix + suffix, "main");
    }

    List<String> repositories = listRepositories();
    assertEquals(
        List.of(prefix + "-a", prefix + "-b", prefix + "-c"),
        repositories.stream().filter(id -> id.startsWith(prefix)).toList());
    assertEquals(
        repositories.stream().sorted().toList(),
        repositories,
        "the whole listing is sorted, not merely the ids one test happened to create");
  }

  @Test
  public void theListingRouteLeavesThePerRepositoryRoutesAlone() throws Exception {
    // The collection sits one segment above every route that was there first, and a listing that
    // swallowed info/refs would break every clone on this platform while looking like a new feature
    // working.
    String repoId = seedOrigin();
    assertTrue(listRepositories().contains(repoId));

    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-upload-pack-advertisement"));
    given()
        .when()
        .get("/artifacts/git/" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repoId", equalTo(repoId));
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
