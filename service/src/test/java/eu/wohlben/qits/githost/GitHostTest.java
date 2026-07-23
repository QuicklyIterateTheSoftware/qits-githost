package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Verifies the in-process JGit smart-HTTP server ({@link GitHostRoutes} at {@code /git/*}) that
 * workspace containers clone from and push to: a real {@code git clone} + {@code push} round-trip
 * moves the ref in the served bare origin, an unknown repo id is a 404, and a traversal-shaped id
 * is rejected. No docker is involved — this exercises only the git hosting.
 */
@QuarkusTest
@TestProfile(GitHostTest.TestProfile.class)
public class GitHostTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-githost-test");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;

  @TestHTTPResource("/git")
  URL gitBase;

  private final String fixtureUrl;

  public GitHostTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  /** Seeds a bare origin at {@code <data-dir>/<repoId>/origin} from the fixture. */
  private String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path origin = Path.of(dataDir, repoId, "origin");
    Files.createDirectories(origin.getParent());
    runGit(null, "git", "clone", "--bare", fixtureUrl, origin.toString());
    return repoId;
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
  public void infoRefsAdvertisesUploadPackForAKnownRepo() throws Exception {
    String repoId = seedOrigin();
    given()
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
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
        .get("/git/" + repoId + "/info/refs")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }

  @Test
  public void unknownRepoIdIs404() {
    given()
        .when()
        .get("/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void traversalShapedIdIsRejected() {
    // A dotted name can't match the strict repo-id slug, so the resolver refuses it (404) rather
    // than letting it walk out of the data dir.
    given()
        .when()
        .get("/git/foo.bar/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void nameAddressedCloneResolvesThroughTheAliasTable() throws Exception {
    // The full import path registers the repo's url-basename ("testing-repo") as a project-scoped
    // name alias, so it is servable at /git/<projectId>/<name> as well as /git/<repoId>.
    var project = projectService.create("GitHost Names", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);

    Path clone = Files.createTempDirectory("qits-githost-named-clone");
    Files.delete(clone);
    runGit(null, "git", "clone", gitBase + "/" + project.id + "/testing-repo", clone.toString());
    assertTrue(
        Files.exists(clone.resolve(".git")), "name-addressed clone should produce a working copy");

    // A trailing .git on the name segment is stripped before the alias lookup.
    Path cloneDotGit = Files.createTempDirectory("qits-githost-named-clone-dotgit");
    Files.delete(cloneDotGit);
    runGit(
        null,
        "git",
        "clone",
        gitBase + "/" + project.id + "/testing-repo.git",
        cloneDotGit.toString());
    assertTrue(
        Files.exists(cloneDotGit.resolve(".git")), "a .git suffix on the name still resolves");

    // The id-addressed route keeps working for the same repo (back-compat).
    given()
        .when()
        .get("/git/" + repo.id + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void unknownNameInProjectIs404() {
    var project = projectService.create("GitHost Missing Name", null);
    given()
        .when()
        .get("/git/" + project.id + "/no-such-name/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  private String runGit(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      throw new RuntimeException("git " + String.join(" ", command) + " failed:\n" + out);
    }
    return out;
  }
}
