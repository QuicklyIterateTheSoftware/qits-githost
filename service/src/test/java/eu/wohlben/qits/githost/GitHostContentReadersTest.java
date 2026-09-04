package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.githost.content-readers} SET beside {@code qits.githost.storage-client} — the
 * id-addressed CONTENT reads opened to a named set of identities, and the rest of that scheme left
 * exactly as {@link GitHostStorageClientTest} finds it.
 *
 * <p>A class of its own for the same reason that one is: the pair of keys is a process
 * configuration. What it holds is the distinction the keys exist to make — <b>a blob read is not
 * the storage scheme</b>. Speaking {@code /git/<repoId>} to clone, push, provision, delete or
 * enumerate still has to mean "the caller IS qits-projects"; asking for the bytes of one path at
 * one revision is an act the platform's own deployer performs with an id it was handed and a name
 * it was never given.
 *
 * <p>Measured on 2026-09-04, which is what this class is written from: thirteen deployments failed
 * {@code deployment spec unreadable … the git host answered 403}, one per release, because
 * qits-deployments reads {@code .config/qits/deployments.yml} at the released tag and a {@code
 * SoftwareRelease} carries the repository as its storage id.
 */
@QuarkusTest
@TestProfile(GitHostContentReadersTest.ContentReadersConfigured.class)
public class GitHostContentReadersTest {

  static final String STORAGE_CLIENT = "qits-projects";

  static final String STORAGE_ROLE = "clients/" + STORAGE_CLIENT;

  /** The forwarded-header identity the platform's deployer actually arrives as. */
  static final String SYSTEM_ROLE = "qits:system";

  /** The other spelling a reader may take: a peer presenting its own bearer, by self-role. */
  static final String READER_CLIENT_ROLE = "clients/qits-deployments";

  public static class ContentReadersConfigured implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.githost.storage-client",
          STORAGE_CLIENT,
          "qits.githost.content-readers",
          SYSTEM_ROLE + "," + READER_CLIENT_ROLE);
    }
  }

  @Inject FakeRepositoryNameResolver repositoryNames;

  @Inject GitRepositoryProvider repositories;

  @TestHTTPResource("/git")
  URL gitBase;

  private String repoId;

  private final String projectId = UUID.randomUUID().toString();

  /** Seeded name-addressed, for {@link GitHostStorageClientTest#seed}'s reason. */
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

  /** See {@link GitHostStorageClientTest#withRoles} for why the identity arrives this way. */
  private static io.restassured.specification.RequestSpecification withRoles(String roles) {
    return given().header("X-Qits-User", "content-readers-test").header("X-Qits-Roles", roles);
  }

  private void assertContentReadsAnswer(String roles, int status) {
    withRoles(roles).when().get("/git/" + repoId + "/blob/main/README.md").then().statusCode(status);
    withRoles(roles).when().get("/git/" + repoId + "/tree/main").then().statusCode(status);
    withRoles(roles).when().get("/git/" + repoId + "/tree/main/").then().statusCode(status);
  }

  /**
   * Everything on the id-addressed scheme that is NOT a content read, as one list, so a route that
   * quietly joins the content family fails here.
   *
   * <p>DELETE is last for {@link GitHostStorageClientTest}'s reason: it takes the repository away.
   */
  private void assertTheRestOfTheSchemeAnswers(String roles, int status) {
    withRoles(roles).when().get("/git").then().statusCode(status);
    withRoles(roles)
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(status);
    withRoles(roles)
        .when()
        .post("/git/" + repoId + "/git-receive-pack")
        .then()
        .statusCode(status);
    withRoles(roles).when().get("/git/" + repoId).then().statusCode(status);
    withRoles(roles).when().head("/git/" + repoId).then().statusCode(status);
    withRoles(roles)
        .contentType(ContentType.JSON)
        .body("{\"defaultBranch\":\"main\"}")
        .when()
        .put("/git/" + UUID.randomUUID())
        .then()
        .statusCode(status);
    withRoles(roles).when().delete("/git/" + repoId).then().statusCode(status);
  }

  @Test
  public void aSanctionedReaderReadsBlobsAndTreesByStorageId() {
    // The whole point of the key: this is the deployer's spec read, and it was a 403.
    assertContentReadsAnswer(SYSTEM_ROLE, Response.Status.OK.getStatusCode());
    assertContentReadsAnswer(READER_CLIENT_ROLE, Response.Status.OK.getStatusCode());
  }

  @Test
  public void andNothingElseOnThatSchemeMoved() {
    // Receive-pack and the lifecycle stay as closed as they were: the list admits READERS.
    assertTheRestOfTheSchemeAnswers(SYSTEM_ROLE, Response.Status.FORBIDDEN.getStatusCode());
    assertTheRestOfTheSchemeAnswers(READER_CLIENT_ROLE, Response.Status.FORBIDDEN.getStatusCode());
  }

  @Test
  public void anIdentityOnNeitherListReadsNothingByStorageId() {
    // qits:admin is not on the list, so it meets the guard it always met — the key is a list of
    // sanctioned identities, not a relaxation of the scheme.
    assertContentReadsAnswer("qits:admin", Response.Status.FORBIDDEN.getStatusCode());
    withRoles("qits:admin")
        .when()
        .get("/git/" + repoId + "/blob/main/README.md")
        .then()
        .body(containsString("/git/<projectId>/<repoName>"))
        .body(containsString(STORAGE_ROLE));
  }

  @Test
  public void theStorageClientKeepsTheWholeScheme() {
    assertContentReadsAnswer(STORAGE_ROLE, Response.Status.OK.getStatusCode());
    withRoles(STORAGE_ROLE)
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  @Test
  public void theNameAddressedSchemeIsUntouchedByEitherKey() {
    withRoles(SYSTEM_ROLE)
        .when()
        .get("/git/" + projectId + "/testing-repo/blob/main/README.md")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    withRoles(SYSTEM_ROLE)
        .when()
        .get("/git/" + projectId + "/testing-repo/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-upload-pack-advertisement"));
  }

  @Test
  public void aResolverOutageIsA503AndNeverAFallThroughTo403() {
    // The failure this whole fix is about, in its other half: qits-projects unreachable must be a
    // retryable upstream answer, never a refusal about an address the caller never spelled. The
    // hand-off runs AFTER the resolver has answered, so an outage never reaches it.
    repositoryNames.beUnavailable(true);
    try {
      for (String path :
          new String[] {
            "/git/" + projectId + "/testing-repo/blob/main/README.md",
            "/git/" + projectId + "/testing-repo/tree/main",
            "/git/" + projectId + "/testing-repo/info/refs?service=git-upload-pack"
          }) {
        withRoles(SYSTEM_ROLE)
            .when()
            .get(path)
            .then()
            .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
      }
      // …and for an identity the content list does not carry either, so the 503 is the route's
      // answer rather than a grant's.
      withRoles("qits:admin")
          .when()
          .get("/git/" + projectId + "/testing-repo/blob/main/README.md")
          .then()
          .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
    } finally {
      repositoryNames.beUnavailable(false);
    }
  }

  @Test
  public void aNameThatResolvesToNoRepositoryIs404RatherThanTheIdSchemesRefusal() {
    // The overlap shape: /git/<projectId>/blob/blob/<rev>/<path> is a name-addressed read of a
    // repository CALLED blob and an id-addressed read of one called <projectId> at a revision
    // called blob, and the name routes are tried first. A name that RESOLVES has settled the
    // reading — so a store holding nothing at the id it resolves to is a 404, not a hand-off that
    // ends in a 403 about a storage id nobody used.
    repositoryNames.register(projectId, "blob", UUID.randomUUID().toString());
    withRoles("qits:admin")
        .when()
        .get("/git/" + projectId + "/blob/blob/main/README.md")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void aNameAddressedCloneOfARepositoryCalledBlobStillReachesItsRoute() {
    // The hand-off itself, unchanged: a MISS is still handed down, which is what keeps a repository
    // clonable whatever it is called.
    repositoryNames.register(projectId, "blob", repoId);
    withRoles(SYSTEM_ROLE)
        .when()
        .get("/git/" + projectId + "/blob/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .contentType(containsString("git-upload-pack-advertisement"));
  }
}
