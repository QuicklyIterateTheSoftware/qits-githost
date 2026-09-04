package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The three primitives beside the merge: {@code POST …/tags}, {@code POST …/commits} and {@code
 * DELETE …/branches/{name}}.
 *
 * <p>Seeded and verified over the wire, like the rest of this suite — {@code git ls-remote} says
 * what a ref is and the browse endpoint says what a tree holds, so nothing here can pass on a write
 * the served host would not show a real client.
 */
@QuarkusTest
public class RepositoryRefsResourceTest {

  static final String API = "/githost/api/repositories/";

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  /** A repository with {@code main} carrying two files, and a second branch to delete. */
  private String seed() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-refs-seed");
    git(work, "init", "-q", "-b", "main", ".");
    Files.writeString(work.resolve("pom.xml"), "<version>0</version>\n");
    Files.writeString(work.resolve("README.md"), "seed\n");
    commit(work, "seed");
    git(work, "branch", "release/1");
    git(work, "push", "-q", gitBase + "/" + repoId, "main", "release/1");
    return repoId;
  }

  // --- the tag -------------------------------------------------------------------------------

  @Test
  public void aTagIsAnnotatedAndPointsAtTheCommit() throws Exception {
    String repo = seed();
    String head = lsRemote(repo, "refs/heads/main", "refs/heads/main");
    JsonPath tagged =
        post(repo, "/tags", tag("2026.903.120000", "refs/heads/main", "released"))
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();

    assertThat(tagged.getString("tag"), is("refs/tags/2026.903.120000"));
    assertThat(tagged.getString("object"), is(head));
    // The ref names a TAG OBJECT, not the commit — which is what makes it annotated, and the only
    // way to see that over the wire is that the advertisement carries the peeled line.
    assertThat(tagged.getString("sha"), not(head));
    assertThat(
        lsRemote(repo, "refs/tags/2026.903.120000*", "refs/tags/2026.903.120000"),
        is(tagged.getString("sha")));
    assertThat(
        lsRemote(repo, "refs/tags/2026.903.120000*", "refs/tags/2026.903.120000^{}"), is(head));
  }

  @Test
  public void anExistingTagIsRefusedWithAnAnswerOfItsOwn() throws Exception {
    // The version-uniqueness guarantee: a caller stamps a version, asks for the tag, and learns
    // from the answer's own code that the name is taken rather than that something went wrong.
    String repo = seed();
    String first =
        post(repo, "/tags", tag("2026.903.130000", "refs/heads/main", "first"))
            .then()
            .statusCode(201)
            .extract()
            .path("sha");

    post(repo, "/tags", tag("2026.903.130000", "refs/heads/main", "again"))
        .then()
        .statusCode(409)
        .body("error", is("tag-exists"))
        .body("tag", is("refs/tags/2026.903.130000"))
        .body("sha", is(first));

    // Refused, not overwritten: the ref still says what the first call made it say.
    assertThat(
        lsRemote(repo, "refs/tags/2026.903.130000*", "refs/tags/2026.903.130000"), is(first));
    // And a tag pushed by an ordinary client is refused just the same — the check is the REF, not
    // a memory of what this endpoint did.
    post(repo, "/tags", tag("refs/tags/2026.903.130000", "refs/heads/main", "full ref spelling"))
        .then()
        .statusCode(409)
        .body("error", is("tag-exists"));
  }

  @Test
  public void aTagOfNothingIsNamedRatherThanGuessed() throws Exception {
    String repo = seed();
    post(repo, "/tags", tag("2026.903.140000", "refs/heads/nope", "x"))
        .then()
        .statusCode(404)
        .body("error", is("no-such-object"));
    post(repo, "/tags", tag("", "refs/heads/main", "x")).then().statusCode(400);
    post(repo, "/tags", tag("a b", "refs/heads/main", "x")).then().statusCode(400);
    post(UUID.randomUUID().toString(), "/tags", tag("v1", "refs/heads/main", "x"))
        .then()
        .statusCode(404)
        .body("error", is("no-such-repository"));
  }

  // --- the commit ----------------------------------------------------------------------------

  @Test
  public void filesAreWrittenAsOneCommitOnTheTip() throws Exception {
    String repo = seed();
    String tip = lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1");

    Map<String, Object> body = commitBody("refs/heads/release/1", "bump the manifests");
    body.put(
        "files",
        Map.of("pom.xml", "<version>2026.903.1</version>\n", "web/package.json", "{\"v\":1}\n"));
    JsonPath committed = post(repo, "/commits", body).then().statusCode(200).extract().jsonPath();

    assertThat(committed.getString("outcome"), is("committed"));
    assertThat(committed.getString("parent"), is(tip));
    assertThat(committed.getString("sha"), not(tip));
    // One commit, and the ref advanced onto it.
    assertThat(
        lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1"),
        is(committed.getString("sha")));
    given()
        .queryParam("rev", "release/1")
        .queryParam("path", "pom.xml")
        .when()
        .get(API + repo + "/file")
        .then()
        .statusCode(200)
        .body("content", is("<version>2026.903.1</version>\n"));
    // A path that did not exist arrives with the directories it implies.
    assertThat(
        given()
            .queryParam("rev", "release/1")
            .when()
            .get(API + repo + "/tree")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("paths", String.class),
        hasItem("web/package.json"));
  }

  @Test
  public void anEditThatChangesNothingWritesNothing() throws Exception {
    String repo = seed();
    String tip = lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1");
    Map<String, Object> body = commitBody("refs/heads/release/1", "same bytes");
    body.put("files", Map.of("pom.xml", "<version>0</version>\n"));

    JsonPath answer = post(repo, "/commits", body).then().statusCode(200).extract().jsonPath();
    assertThat(answer.getString("outcome"), is("unchanged"));
    assertThat(answer.getString("sha"), is(tip));
    assertThat(lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1"), is(tip));
  }

  @Test
  public void aDeletionIsPartOfTheSameCommit() throws Exception {
    String repo = seed();
    Map<String, Object> body = commitBody("refs/heads/release/1", "drop the readme, keep the pom");
    body.put("files", Map.of("pom.xml", "<version>1</version>\n"));
    body.put("deletePaths", List.of("README.md", "never/was/there.txt"));

    post(repo, "/commits", body).then().statusCode(200).body("outcome", is("committed"));
    List<String> paths =
        given()
            .queryParam("rev", "release/1")
            .when()
            .get(API + repo + "/tree")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("paths", String.class);
    assertThat(paths, not(hasItem("README.md"))); // gone
    assertThat(paths, hasItem("pom.xml")); // and a deletion of a path that was never there is not
    // an error: the request describes the tree the caller wants.
  }

  @Test
  public void aCommitOntoNothingIsRefusedBeforeAnythingIsWritten() throws Exception {
    String repo = seed();
    Map<String, Object> missing = commitBody("refs/heads/no-such-branch", "x");
    missing.put("files", Map.of("a.txt", "a\n"));
    post(repo, "/commits", missing).then().statusCode(404).body("error", is("no-such-ref"));

    Map<String, Object> escaping = commitBody("refs/heads/release/1", "x");
    escaping.put("files", Map.of("../etc/passwd", "no\n"));
    post(repo, "/commits", escaping).then().statusCode(400);

    Map<String, Object> silent = commitBody("refs/heads/release/1", null);
    silent.put("files", Map.of("a.txt", "a\n"));
    post(repo, "/commits", silent).then().statusCode(400);

    post(repo, "/commits", commitBody("refs/heads/release/1", "nothing to do"))
        .then()
        .statusCode(400);
  }

  // --- the branch delete ---------------------------------------------------------------------

  @Test
  public void aBranchIsDeletedAndSayingItTwiceIsNotTheSameAnswer() throws Exception {
    String repo = seed();
    assertThat(lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1"), notNullValue());

    // The slashy name rides the path tail; no encoding dance.
    given().when().delete(API + repo + "/branches/release/1").then().statusCode(204);
    assertThat(lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1"), nullValue());

    given()
        .when()
        .delete(API + repo + "/branches/release/1")
        .then()
        .statusCode(404)
        .body("error", is("no-such-branch"));
  }

  @Test
  public void theDefaultBranchIsRefusedTheSameWayThePushDoorRefusesIt() throws Exception {
    String repo = seed();
    // Unconditionally — not under the push hook's protect-default-branch switch, which ships off.
    given()
        .when()
        .delete(API + repo + "/branches/main")
        .then()
        .statusCode(409)
        .body("error", is("protected-branch"));
    given()
        .when()
        .delete(API + repo + "/branches/refs/heads/main")
        .then()
        .statusCode(409)
        .body("error", is("protected-branch"));
    assertThat(lsRemote(repo, "refs/heads/main", "refs/heads/main"), notNullValue());
  }

  @Test
  public void aGitlinkPinIsWrittenAsASubmoduleEntry() throws Exception {
    String repo = seed();
    String pinned = "1234567890abcdef1234567890abcdef12345678";
    Map<String, Object> body = commitBody("refs/heads/release/1", "bank the estate");
    body.put("files", Map.of(".gitmodules", "[submodule \"y\"]\n\tpath = components/x/y\n"));
    body.put("gitlinks", Map.of("components/x/y", pinned));

    post(repo, "/commits", body).then().statusCode(200).body("outcome", is("committed"));

    // The entry is mode 160000 with the pinned sha — a real clone's reading, because a gitlink is
    // invisible to the browse tree (it is not a blob) and only git itself says the mode.
    Path clone = Files.createTempDirectory("qits-gitlink-clone");
    git(null, "clone", "-q", "--branch", "release/1", gitBase + "/" + repo, clone.toString());
    assertThat(
        git(clone, "ls-tree", "HEAD", "components/x/y").trim(),
        is("160000 commit " + pinned + "\tcomponents/x/y"));

    // The same pin again is the tree that is already there: no commit, no ref move.
    Map<String, Object> again = commitBody("refs/heads/release/1", "bank the same estate");
    again.put("gitlinks", Map.of("components/x/y", pinned));
    post(repo, "/commits", again).then().statusCode(200).body("outcome", is("unchanged"));
  }

  @Test
  public void aGitlinkPinsAFullShaAndAPathIsOneKindOnly() throws Exception {
    String repo = seed();
    Map<String, Object> shortSha = commitBody("refs/heads/release/1", "x");
    shortSha.put("gitlinks", Map.of("components/x/y", "123abc"));
    post(repo, "/commits", shortSha).then().statusCode(400);

    Map<String, Object> bothKinds = commitBody("refs/heads/release/1", "x");
    bothKinds.put("files", Map.of("components/x/y", "content"));
    bothKinds.put("gitlinks", Map.of("components/x/y", "1234567890abcdef1234567890abcdef12345678"));
    post(repo, "/commits", bothKinds).then().statusCode(400);
  }

  @Test
  public void aBranchIsReadBackWithItsHead() throws Exception {
    String repo = seed();
    String tip = lsRemote(repo, "refs/heads/main", "refs/heads/main");
    given()
        .when()
        .get(API + repo + "/branches/main")
        .then()
        .statusCode(200)
        .body("ref", is("refs/heads/main"))
        .body("sha", is(tip));
    given().when().get(API + repo + "/branches/never-was-there").then().statusCode(404);
  }

  @Test
  public void aBrowserSessionIsNotAMachineOnAnyOfThem() throws Exception {
    String repo = seed();
    given()
        .header("X-Qits-User", "a-person")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body(tag("2026.903.150000", "refs/heads/main", "x"))
        .when()
        .post(API + repo + "/tags")
        .then()
        .statusCode(403);
    given()
        .header("X-Qits-User", "a-person")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .delete(API + repo + "/branches/release/1")
        .then()
        .statusCode(403);
    assertThat(lsRemote(repo, "refs/heads/release/1", "refs/heads/release/1"), notNullValue());
  }

  // --- the plumbing -------------------------------------------------------------------------

  private io.restassured.response.Response post(String repoId, String path, Object body) {
    return given().contentType(ContentType.JSON).body(body).when().post(API + repoId + path);
  }

  private static Map<String, Object> tag(String name, String sha, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("sha", sha);
    body.put("message", message);
    return body;
  }

  private static Map<String, Object> commitBody(String ref, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ref", ref);
    if (message != null) {
      body.put("message", message);
    }
    return body;
  }

  /** What the served repository advertises for a ref, or {@code null} when it advertises none. */
  private String lsRemote(String repoId, String pattern, String wanted) throws Exception {
    String out = git(null, "ls-remote", gitBase + "/" + repoId, pattern);
    for (String line : out.split("\n")) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length == 2 && parts[1].equals(wanted)) {
        return parts[0];
      }
    }
    return null;
  }

  private static void commit(Path work, String message) throws Exception {
    git(work, "add", ".");
    git(work, "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m", message);
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }
}
