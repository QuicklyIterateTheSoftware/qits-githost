package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.githost.storage-client} SET — the id-addressed scheme closed to everyone but
 * qits-projects' own service client.
 *
 * <p>A class of its own because the key is a process configuration, and because it inverts what
 * every other class here assumes: with the guard armed, {@code /git/<repoId>} is not a public
 * address at all. The role it demands is the client's SELF-ROLE, {@code clients/<client id>}, which
 * qits-idp mints into that client's bearers and into nobody else's — so this is not "another
 * privilege to grant", it is "the caller IS qits-projects".
 *
 * <p><b>{@code qits:admin} and {@code qits:system} do not open it, and that is the point.</b> The
 * platform's privileged roles are exactly what a caller reaching for a storage url would be holding;
 * if they worked, the scheme would be internal by convention and not by construction.
 *
 * <p>{@link GitHostTest} covers the UNSET arm — every id-addressed route there is served under the
 * class-wide policy alone, which is what a live platform runs until the cutover.
 */
@QuarkusTest
@TestProfile(GitHostStorageClientTest.StorageClientConfigured.class)
public class GitHostStorageClientTest {

  /** The client id the guard is configured with; the role is this, prefixed with {@code clients/}. */
  static final String STORAGE_CLIENT = "qits-projects";

  static final String STORAGE_ROLE = "clients/" + STORAGE_CLIENT;

  public static class StorageClientConfigured implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.githost.storage-client", STORAGE_CLIENT);
    }
  }

  @Inject FakeRepositoryNameResolver repositoryNames;

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  private String repoId;

  private final String projectId = UUID.randomUUID().toString();

  /**
   * Seeds through the NAME-addressed url on purpose: the id-addressed one is what this class closes,
   * so a fixture that pushed there would be proving the guard against itself before every case.
   */
  @BeforeEach
  void seed() throws Exception {
    repositoryNames.clear();
    repoId = GitHostFixture.emptyOrigin(repositories);
    repositoryNames.register(projectId, "testing-repo", repoId);
    GitHostFixture.git(
        GitHostFixture.localRepo(),
        "git",
        "push",
        "-q",
        gitBase + "/" + projectId + "/testing-repo",
        "main");
  }

  /**
   * Every id-addressed route, as one list, so a route added without a guard fails here.
   *
   * <p>DELETE is last, and it is the one entry whose served answer is not 200: it takes the seeded
   * repository away, so every other route has to have been asked before it. The guard is what this
   * list checks, so the served status is mapped rather than the case being left out.
   */
  private void assertIdAddressedRoutesAnswer(int status) {
    given().when().get("/git").then().statusCode(status);
    given()
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(status);
    given().when().get("/git/" + repoId).then().statusCode(status);
    given().when().head("/git/" + repoId).then().statusCode(status);
    given().when().get("/git/" + repoId + "/blob/main/README.md").then().statusCode(status);
    given().when().get("/git/" + repoId + "/tree/main").then().statusCode(status);
    given().when().get("/git/" + repoId + "/tree/main/").then().statusCode(status);
    given()
        .when()
        .delete("/git/" + repoId)
        .then()
        .statusCode(
            status == Response.Status.OK.getStatusCode()
                ? Response.Status.NO_CONTENT.getStatusCode()
                : status);
  }

  @Test
  @TestSecurity(user = "qits-projects", roles = STORAGE_ROLE)
  public void theStorageClientsSelfRoleOpensTheWholeIdAddressedScheme() {
    assertIdAddressedRoutesAnswer(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/git/" + UUID.randomUUID())
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());
  }

  @Test
  @TestSecurity(user = "admin", roles = "qits:admin")
  public void adminDoesNotOpenTheStorageScheme() {
    assertIdAddressedRoutesAnswer(Response.Status.FORBIDDEN.getStatusCode());
  }

  @Test
  @TestSecurity(user = "a-service", roles = "qits:system")
  public void aSystemTokenDoesNotOpenItEither() {
    assertIdAddressedRoutesAnswer(Response.Status.FORBIDDEN.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/git/" + UUID.randomUUID())
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
    given()
        .when()
        .post("/git/" + repoId + "/git-receive-pack")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }

  @Test
  @TestSecurity(user = "a-service", roles = "qits:system")
  public void theRefusalNamesTheAddressToUseInstead() {
    // A 403 a caller cannot act on would send them looking for a missing grant. The one thing to do
    // about this refusal is to stop holding a storage id, so the message says so.
    given()
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode())
        .body(containsString("/git/<projectId>/<repoName>"))
        .body(containsString(STORAGE_ROLE));
  }

  @Test
  @TestSecurity(user = "a-service", roles = "qits:system")
  public void theNameAddressedSchemeIsUntouchedByTheGuard() {
    // The public scheme keeps the policy it always had — this guard closes one address, it does not
    // narrow who may clone.
    given()
        .when()
        .get("/git/" + projectId + "/testing-repo/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-upload-pack-advertisement"));
    given()
        .when()
        .get("/git/" + projectId + "/testing-repo/blob/main/README.md")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    given()
        .when()
        .get("/git/" + projectId + "/testing-repo/tree/main")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  @TestSecurity(user = "a-service", roles = "qits:system")
  public void aNameAddressedCloneOfARepositoryCalledBlobStillReachesItsRoute() {
    // The hand-off the id-addressed content routes carry must survive the guard: that is why the
    // check lives inside those two handlers rather than in front of them. Guarded from the front,
    // this request would be a 403 before the router ever tried the clone route.
    repositoryNames.register(projectId, "blob", repoId);
    given()
        .when()
        .get("/git/" + projectId + "/blob/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-upload-pack-advertisement"));
  }
}
