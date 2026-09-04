package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The {@code githost-browse} policy, met by a caller with no identity at all.
 *
 * <p>A class of its own because the everyday suite cannot ask this question: qits-auth-core's
 * {@code %test} synthetic identity answers the HTTP-layer policy check with {@code qits:admin}
 * before {@code @TestSecurity} could substitute anything, so every request in the sibling classes
 * passes the gate by construction. Clearing {@code qits.auth.forward.dev-user} makes the request
 * genuinely anonymous — what the policy answers then is the contract: the per-repository content
 * reads are refused, the id catalogue beside them stays served.
 */
@QuarkusTest
@TestProfile(RepositoryBrowseAuthTest.NoSyntheticIdentity.class)
public class RepositoryBrowseAuthTest {

  public static class NoSyntheticIdentity implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.auth.forward.dev-user", "");
    }
  }

  @Test
  public void anAnonymousCallerIsRefusedTheContentReadsButNotTheCatalogue() {
    String repo = UUID.randomUUID().toString();
    given().when().get("/githost/api/repositories/" + repo).then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
    given().when().get("/githost/api/repositories/" + repo + "/tree").then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
    given().when().get("/githost/api/repositories/" + repo + "/file?path=README.md").then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
    given().when().get("/githost/api/repositories/" + repo + "/loc").then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
    // The write primitives sit under the same wildcard, so the policy answers them too — before
    // their own machine-role annotation ever runs. An unauthenticated caller never reaches JAX-RS.
    given().contentType("application/json")
        .body("{\"target\":\"refs/heads/release/1\",\"sources\":[\"refs/heads/main\"]}")
        .when().post("/githost/api/repositories/" + repo + "/merges").then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
    given().when().get("/githost/api/repositories").then()
        .statusCode(Response.Status.OK.getStatusCode());
  }
}
