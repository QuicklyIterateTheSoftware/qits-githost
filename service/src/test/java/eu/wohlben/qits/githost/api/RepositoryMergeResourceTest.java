package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
 * {@code POST /githost/api/repositories/{repoId}/merges} — the in-core octopus.
 *
 * <p>Seeded through the served git endpoint like the rest of this suite: receive-pack is the only
 * other door this storage has, and a DFS repository has no directory to build a fixture in. The
 * git helpers are re-spelled here rather than shared, the trade {@code RepositoryBrowseResourceTest}
 * documents for the same reason ({@code GitHostFixture} is package-private to the git-route tests).
 *
 * <p>Everything is asserted through the API and the wire — the merge response says what the ref now
 * is, the browse endpoint beside it says what the merged tree holds. Nothing here opens a
 * repository in-process, so nothing here can pass on a merge the served host would not answer.
 */
@QuarkusTest
public class RepositoryMergeResourceTest {

  static final String API = "/githost/api/repositories/";

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  /** A repository with {@code main} and three branches, each adding one file of its own. */
  private String seedThreeBranches() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-seed");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "base.txt", "base\n");
    commit(work, "base");
    for (String branch : List.of("a", "b", "c")) {
      git(work, "checkout", "-q", "-b", "feature/" + branch, "main");
      write(work, branch + ".txt", branch + "\n");
      commit(work, branch);
    }
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/a", "feature/b", "feature/c");
    return repoId;
  }

  @Test
  public void threeSourcesFoldIntoOneCommitWithThreeParents() throws Exception {
    String repo = seedThreeBranches();
    JsonPath merged =
        merge(repo, request("refs/heads/release/1", "feature/a", "feature/b", "feature/c"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(merged.getString("outcome"), is("merged"));
    // One commit, three parents — a pairwise fold that ended in ONE octopus, not a chain of
    // two-parent merges.
    assertThat(merged.getList("parents", String.class), hasSize(3));
    // And the fold really folded: every branch's file is in the merged tree.
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
    assertThat(paths, hasItem("a.txt"));
    assertThat(paths, hasItem("b.txt"));
    assertThat(paths, hasItem("c.txt"));
  }

  @Test
  public void theTargetsOwnTipIsTheFirstParentOfTheNextFold() throws Exception {
    // git octopus folds onto HEAD, and the target ref is what plays HEAD here: an existing target
    // is a head like any other, and the first one — so N sources over an existing target produce a
    // commit with N+1 parents.
    String repo = seedThreeBranches();
    String first =
        merge(repo, request("refs/heads/release/2", "feature/a", "feature/b", "feature/c"))
            .then()
            .statusCode(200)
            .extract()
            .path("sha");

    Path work = Files.createTempDirectory("qits-merge-more");
    git(work, "clone", "-q", gitBase + "/" + repo, ".");
    for (String branch : List.of("a", "b", "c")) {
      git(work, "checkout", "-q", "-B", "feature/" + branch, "origin/feature/" + branch);
      write(work, branch + "-more.txt", branch + "\n");
      commit(work, branch + " again");
    }
    push(work, repo, "feature/a", "feature/b", "feature/c");

    JsonPath second =
        merge(repo, request("refs/heads/release/2", "feature/a", "feature/b", "feature/c"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(second.getString("outcome"), is("merged"));
    assertThat(second.getList("parents", String.class), hasSize(4));
    assertThat(second.getList("parents", String.class).get(0), is(first));
  }

  @Test
  public void asecondIdenticalCallCreatesNoCommit() throws Exception {
    String repo = seedThreeBranches();
    Map<String, Object> body = request("refs/heads/release/3", "feature/a", "feature/b");

    JsonPath first = merge(repo, body).then().statusCode(200).extract().jsonPath();
    assertThat(first.getString("outcome"), is("merged"));

    JsonPath again = merge(repo, body).then().statusCode(200).extract().jsonPath();
    // The heads did not move, so every one of them is contained in the target's tip and drops out;
    // the target is the only head left and it already says the right thing. Same sha, no commit.
    assertThat(again.getString("outcome"), is("unchanged"));
    assertThat(again.getString("sha"), is(first.getString("sha")));
    // Ordering the sources differently is the same question and gets the same answer.
    JsonPath reordered =
        merge(repo, request("refs/heads/release/3", "feature/b", "feature/a"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(reordered.getString("outcome"), is("unchanged"));
    assertThat(reordered.getString("sha"), is(first.getString("sha")));
  }

  @Test
  public void aHeadAnotherHeadAlreadyContainsIsSkippedRatherThanMerged() throws Exception {
    // feature/a descends from main, so main is contained in it and contributes nothing — git's own
    // "Already up to date". One head is left, so the target is moved onto it and NO merge commit
    // with a single parent is written.
    String repo = seedThreeBranches();
    JsonPath answer =
        merge(repo, request("refs/heads/release/4", "refs/heads/main", "feature/a"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("outcome"), is("fast-forward"));
    assertThat(answer.getList("skipped", String.class), contains("refs/heads/main"));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/feature/a")));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/release/4")));

    // And asking again moves nothing: the target IS the surviving head now.
    JsonPath again =
        merge(repo, request("refs/heads/release/4", "refs/heads/main", "feature/a"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(again.getString("outcome"), is("unchanged"));
  }

  @Test
  public void aConflictIsReportedWithItsPathsAndItsHeadAndMovesNoRef() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    Path work = Files.createTempDirectory("qits-merge-conflict");
    git(work, "init", "-q", "-b", "main", ".");
    write(work, "shared.txt", "base\n");
    write(work, "quiet.txt", "untouched\n");
    commit(work, "base");
    git(work, "checkout", "-q", "-b", "feature/left", "main");
    write(work, "shared.txt", "left\n");
    commit(work, "left");
    git(work, "checkout", "-q", "-b", "feature/right", "main");
    write(work, "shared.txt", "right\n");
    commit(work, "right");
    git(work, "checkout", "-q", "main");
    push(work, repoId, "main", "feature/left", "feature/right");

    JsonPath conflict =
        merge(repoId, request("refs/heads/release/5", "feature/left", "feature/right"))
            .then()
            .statusCode(409)
            .extract()
            .jsonPath();
    assertThat(conflict.getString("error"), is("merge-conflict"));
    assertThat(conflict.getString("target"), is("refs/heads/release/5"));
    assertThat(conflict.getList("conflicts.path", String.class), contains("shared.txt"));
    // The head being folded in when it broke, spelled as the caller spelled it.
    assertThat(conflict.getList("conflicts.head", String.class), contains("feature/right"));
    assertThat(
        conflict.getList("conflicts.headSha", String.class),
        contains(remoteSha(repoId, "refs/heads/feature/right")));
    // No ref moved, and nothing was left behind for the caller to clean up.
    assertThat(remoteSha(repoId, "refs/heads/release/5"), is(nullSha()));
  }

  @Test
  public void oneSourceOnlyCreatesTheTargetRatherThanAnEmptyOctopus() throws Exception {
    String repo = seedThreeBranches();
    JsonPath answer =
        merge(repo, request("refs/heads/release/6", "refs/heads/main"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("outcome"), is("fast-forward"));
    assertThat(answer.getString("sha"), is(remoteSha(repo, "refs/heads/main")));
    assertThat(answer.getList("parents", String.class), not(hasItem(answer.getString("sha"))));
  }

  @Test
  public void whatTheHostCannotResolveIsNamedRatherThanGuessed() throws Exception {
    String repo = seedThreeBranches();
    merge(UUID.randomUUID().toString(), request("refs/heads/release/7", "refs/heads/main"))
        .then()
        .statusCode(404)
        .body("error", is("no-such-repository"));
    merge(repo, request("refs/heads/release/7", "feature/nope"))
        .then()
        .statusCode(404)
        .body("error", is("no-such-source"));
    merge(repo, request("release/7", "refs/heads/main")).then().statusCode(400);
    merge(repo, request("refs/tags/v1", "refs/heads/main")).then().statusCode(400);
    merge(repo, request("refs/heads/release/7")).then().statusCode(400);
    merge(repo, request("refs/heads/release/7", "main^{tree}")).then().statusCode(400);
  }

  @Test
  public void aBrowserSessionIsNotAMachine() throws Exception {
    String repo = seedThreeBranches();
    // The everyday suite runs as qits-auth-core's synthetic %test identity, which carries both
    // roles; a present forwarded header outranks it, which is how a caller holding only the
    // browser role is spelled (the measured idiom — see GitHostStorageClientTest).
    given()
        .header("X-Qits-User", "a-person")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body(request("refs/heads/release/8", "refs/heads/main"))
        .when()
        .post(API + repo + "/merges")
        .then()
        .statusCode(403);
  }

  // --- the plumbing -------------------------------------------------------------------------

  private io.restassured.response.Response merge(String repoId, Map<String, Object> body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(API + repoId + "/merges");
  }

  private static Map<String, Object> request(String target, String... sources) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("target", target);
    body.put("sources", List.of(sources));
    return body;
  }

  /** What the served repository says a ref is, over the wire, or a null-ish marker when it has none. */
  private String remoteSha(String repoId, String ref) throws Exception {
    String out = git(null, "ls-remote", gitBase + "/" + repoId, ref);
    for (String line : out.split("\n")) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length == 2 && parts[1].equals(ref)) {
        return parts[0];
      }
    }
    return nullSha();
  }

  private static String nullSha() {
    return "";
  }

  private void push(Path work, String repoId, String... refs) throws Exception {
    String[] command = new String[3 + refs.length];
    command[0] = "push";
    command[1] = "-q";
    command[2] = gitBase + "/" + repoId;
    System.arraycopy(refs, 0, command, 3, refs.length);
    git(work, command);
  }

  private static void write(Path work, String name, String content) throws Exception {
    Files.writeString(work.resolve(name), content);
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
