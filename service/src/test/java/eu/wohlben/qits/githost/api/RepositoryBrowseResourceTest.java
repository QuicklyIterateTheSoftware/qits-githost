package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.persistence.RepositoryLocStore;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /githost/api/repositories/{repoId}[/tree|/file]} — the browser plane's content reads.
 *
 * <p>Seeded through the served git endpoint, like the rest of the suite: receive-pack is the only
 * door this storage has, and a DFS repository has no directory to build a fixture in. The helpers
 * are re-spelled here rather than shared because {@code GitHostFixture} is package-private to the
 * git-route tests and this class tests another package — the same trade {@code
 * RepositoriesResourceTest} documents for its severed-pool helper.
 *
 * <p>The suite runs with qits-auth-core's synthetic {@code %test} identity ({@code qits:admin}), so
 * the plain requests here also prove the {@code githost-browse} policy passes the roles it names;
 * {@code RepositoryBrowseAuthTest} proves it refuses a caller without them.
 */
@QuarkusTest
public class RepositoryBrowseResourceTest {

  static final String API = "/githost/api/repositories/";

  @Inject GitRepositoryProvider repositories;
  @Inject RepositoryLocStore locStore;

  @TestHTTPResource("/git")
  URL gitBase;

  /**
   * One repository with everything the endpoints under test care about: a nested tree, a binary
   * blob, a blob past the content cap, a symlink, a submodule gitlink, and a slashy branch. Seeded
   * once for the class — the assertions are all reads.
   */
  private static volatile String seeded;

  private String seededRepo() throws Exception {
    String repo = seeded;
    if (repo == null) {
      synchronized (RepositoryBrowseResourceTest.class) {
        repo = seeded;
        if (repo == null) {
          seeded = repo = seed();
        }
      }
    }
    return repo;
  }

  private String seed() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");

    Path work = Files.createTempDirectory("qits-browse-seed");
    git(work, "init", "-q", "-b", "main", ".");
    Files.writeString(work.resolve("README.md"), "hello browse\n");
    Files.createDirectories(work.resolve("src/app"));
    Files.writeString(work.resolve("src/app/main.txt"), "nested\n");
    Files.write(work.resolve("logo.bin"), new byte[] {0x00, 0x01, 0x02, (byte) 0xff, 0x00});
    // Past MAX_CONTENT_BYTES but plainly text, so the answer proves the cap and not the sniffer.
    Files.writeString(
        work.resolve("large.txt"),
        "x".repeat(80).concat("\n").repeat((RepositoryBrowseResource.MAX_CONTENT_BYTES / 81) + 42));
    Files.createSymbolicLink(work.resolve("link.txt"), Path.of("README.md"));
    // The loc summary's fixture: a main/test pair per classification rule, and one file per skip.
    Files.createDirectories(work.resolve("src/main/java"));
    Files.createDirectories(work.resolve("src/test/java"));
    Files.writeString(work.resolve("src/main/java/App.java"), "line\n".repeat(5)); // main by default
    Files.writeString(
        work.resolve("src/test/java/AppTest.java"), "line\n".repeat(9)); // test by segment
    Files.createDirectories(work.resolve("web"));
    Files.writeString(work.resolve("web/thing.ts"), "line\n".repeat(3)); // main by default
    Files.writeString(work.resolve("web/thing.spec.ts"), "line\n".repeat(4)); // test by suffix
    // A mapped extension past the cap: SQL must stay out of the summary entirely.
    Files.writeString(
        work.resolve("big.sql"),
        "x".repeat(80).concat("\n").repeat((RepositoryBrowseResource.MAX_CONTENT_BYTES / 81) + 42));
    // A mapped extension holding binary bytes: CSS must stay out too.
    Files.write(work.resolve("art.css"), new byte[] {0x00, 0x01, 0x02, (byte) 0xff, 0x00});
    // A symlink spelled like Markdown: its blob is a target string and counts nothing.
    Files.createSymbolicLink(work.resolve("link.md"), Path.of("README.md"));
    git(work, "add", ".");
    commit(work, "seed");
    // A gitlink at `vendored`, pointing at this repository's own tip — receive-pack does not chase
    // gitlinks, so any well-formed sha works, and the tip is one that certainly exists.
    String tip = git(work, "rev-parse", "HEAD").trim();
    git(work, "update-index", "--add", "--cacheinfo", "160000," + tip + ",vendored");
    commit(work, "vendor a submodule pointer");
    git(work, "branch", "feature/slashy");
    git(work, "push", "-q", gitBase + "/" + repoId, "main", "feature/slashy");
    return repoId;
  }

  @Test
  public void describeAnswersDefaultBranchAndBranches() throws Exception {
    JsonPath body =
        given()
            .when()
            .get(API + seededRepo())
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .jsonPath();
    assertThat(body.getString("id"), is(seededRepo()));
    assertThat(body.getString("defaultBranch"), is("main"));
    assertThat(body.getList("branches", String.class), is(List.of("feature/slashy", "main")));
  }

  @Test
  public void describeOfAFreshlyProvisionedOriginSaysEmptyThroughItsBranches() throws Exception {
    // HEAD names an unborn main, so the default branch is real while the branch list is empty —
    // exactly the pair the page reads as "nothing to browse yet".
    String empty = UUID.randomUUID().toString();
    repositories.create(empty, "main");
    JsonPath body =
        given().when().get(API + empty).then().statusCode(200).extract().jsonPath();
    assertThat(body.getString("defaultBranch"), is("main"));
    assertThat(body.getList("branches"), is(List.of()));
    // And the tree of that unborn branch is the rev being absent, not the repository.
    given().when().get(API + empty + "/tree").then().statusCode(404)
        .body("error", is("no-such-rev"));
  }

  @Test
  public void aRepositoryThisHostDoesNotHoldIsNamedAbsent() {
    given().when().get(API + UUID.randomUUID()).then().statusCode(404)
        .body("error", is("no-such-repository"));
  }

  @Test
  public void anIdThatIsNotASlugIsRefusedNotLookedUp() {
    given().when().get(API + "..%2Fetc").then().statusCode(400);
  }

  @Test
  public void treeAnswersEveryBlobPathInOneRead() throws Exception {
    JsonPath body =
        given()
            .when()
            .get(API + seededRepo() + "/tree")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .jsonPath();
    assertThat(body.getString("rev"), is("main"));
    assertThat(body.getString("commitSha"), matchesPattern("[0-9a-f]{40}"));
    List<String> paths = body.getList("paths", String.class);
    assertThat(paths, hasItem("README.md"));
    assertThat(paths, hasItem("src/app/main.txt")); // deep path, directory implied
    assertThat(paths, hasItem("link.txt")); // a symlink is a path like any other
    assertThat(paths, not(hasItem("vendored"))); // the gitlink has nothing to show and is skipped
  }

  @Test
  public void aSlashyBranchNeedsNoEncodingBecauseRevIsAQueryParam() throws Exception {
    given()
        .queryParam("rev", "feature/slashy")
        .when()
        .get(API + seededRepo() + "/tree")
        .then()
        .statusCode(200)
        .body("rev", is("feature/slashy"))
        .body("paths", hasItem("README.md"));
  }

  @Test
  public void aRevTheRepositoryDoesNotHoldIsNamedDistinctlyFromTheRepository() throws Exception {
    given().queryParam("rev", "no-such-branch").when().get(API + seededRepo() + "/tree")
        .then().statusCode(404).body("error", is("no-such-rev"));
  }

  @Test
  public void fileAnswersContentWithItsSize() throws Exception {
    given()
        .queryParam("path", "src/app/main.txt")
        .when()
        .get(API + seededRepo() + "/file")
        .then()
        .statusCode(200)
        .body("path", is("src/app/main.txt"))
        .body("binary", is(false))
        .body("size", is(7))
        .body("content", is("nested\n"));
  }

  @Test
  public void aBinaryBlobIsAnAnswerNotAnError() throws Exception {
    given()
        .queryParam("path", "logo.bin")
        .when()
        .get(API + seededRepo() + "/file")
        .then()
        .statusCode(200)
        .body("binary", is(true))
        .body("content", nullValue());
  }

  @Test
  public void aBlobPastTheCapDegradesToBinaryWithItsRealSize() throws Exception {
    JsonPath body =
        given()
            .queryParam("path", "large.txt")
            .when()
            .get(API + seededRepo() + "/file")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(body.getBoolean("binary"), is(true));
    assertThat(body.getString("content"), nullValue());
    assertThat(
        body.getLong("size") > RepositoryBrowseResource.MAX_CONTENT_BYTES, is(true));
  }

  @Test
  public void aPathTheTreeDoesNotHoldIsNamedDistinctlyFromTheRev() throws Exception {
    given().queryParam("path", "no/such/file.txt").when().get(API + seededRepo() + "/file")
        .then().statusCode(404).body("error", is("no-such-path"));
  }

  @Test
  public void aDirectoryIsNotAFile() throws Exception {
    given().queryParam("path", "src/app").when().get(API + seededRepo() + "/file")
        .then().statusCode(404).body("error", is("no-such-path"));
  }

  @Test
  public void theLocSummaryCountsLinesPerLanguageAndSplitsTestFromMain() throws Exception {
    JsonPath body =
        given()
            .when()
            .get(API + seededRepo() + "/loc")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .jsonPath();
    assertThat(body.getString("commitSha"), matchesPattern("[0-9a-f]{40}"));
    // Sorted largest total first: Java 14, TypeScript 7, Markdown 1 — and nothing else.
    assertThat(body.getList("languages.language", String.class),
        is(List.of("Java", "TypeScript", "Markdown")));
    assertThat(body.getLong("languages[0].mainLines"), is(5L));
    assertThat(body.getLong("languages[0].testLines"), is(9L));
    assertThat(body.getLong("languages[1].mainLines"), is(3L));
    assertThat(body.getLong("languages[1].testLines"), is(4L));
    // README.md only: the link.md symlink at the same target counted nothing.
    assertThat(body.getLong("languages[2].mainLines"), is(1L));
    assertThat(body.getLong("languages[2].testLines"), is(0L));
  }

  @Test
  public void whatTheMapDoesNotNameOrTheScanSkipsAppearsInNoBucket() throws Exception {
    List<String> languages =
        given().when().get(API + seededRepo() + "/loc").then().statusCode(200)
            .extract().jsonPath().getList("languages.language", String.class);
    assertThat(languages, not(hasItem("Other"))); // unknown extensions are omitted, not bucketed
    assertThat(languages, not(hasItem("SQL"))); // big.sql is past the cap
    assertThat(languages, not(hasItem("CSS"))); // art.css is binary
  }

  @Test
  public void aSecondLocReadAnswersTheStoredSummary() throws Exception {
    String first =
        given().when().get(API + seededRepo() + "/loc").then().statusCode(200)
            .extract().asString();
    String sha = JsonPath.from(first).getString("commitSha");
    assertThat(locStore.find(seededRepo(), sha).isPresent(), is(true));
    String second =
        given().when().get(API + seededRepo() + "/loc").then().statusCode(200)
            .extract().asString();
    assertThat(second, is(first));
  }

  @Test
  public void locMirrorsTheTreeEndpointsAbsenceAnswers() throws Exception {
    given().when().get(API + UUID.randomUUID() + "/loc").then().statusCode(404)
        .body("error", is("no-such-repository"));
    given().queryParam("rev", "no-such-branch").when().get(API + seededRepo() + "/loc")
        .then().statusCode(404).body("error", is("no-such-rev"));
    given().queryParam("rev", "-not-a-rev").when().get(API + seededRepo() + "/loc")
        .then().statusCode(400);
  }

  private static void commit(Path work, String message) throws Exception {
    git(work, "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m", message);
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }
}
