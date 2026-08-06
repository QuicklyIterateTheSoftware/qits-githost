package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /artifacts/git} on a host that serves nothing yet.
 *
 * <p>A class rather than a case in {@link GitHostTest}, for the reason {@link GitHostNoCiOptionTest}
 * is one: an empty host is a process configuration. What has to be private here is the
 * <b>database</b> — the host answers this question from the pack catalog, the suite's H2 is one
 * in-memory instance shared by every class in the run, and {@link GitHostTest} fills {@code
 * git_pack} — so this profile names an instance of its own. Flyway migrates it exactly as it does
 * the shared one.
 *
 * <p>The body is asserted as an exact string. The field name is a cross-repo contract — qits-ci
 * reads {@code repositories} — and an empty host is the one answer where a typo'd or absent field
 * still looks plausible to a JSON path assertion.
 */
@QuarkusTest
@TestProfile(GitHostListingEmptyTest.FreshCatalog.class)
public class GitHostListingEmptyTest {

  public static class FreshCatalog implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.datasource.artifacts.jdbc.url",
          "jdbc:h2:mem:artifacts-githost-empty;DB_CLOSE_DELAY=-1");
    }
  }

  @Test
  public void aHostThatServesNoRepositoryAnswersAnEmptyArray() {
    String body =
        given()
            .when()
            .get("/artifacts/git")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(containsString("application/json"))
            .extract()
            .asString();
    assertEquals("{\"repositories\":[]}", body);
  }
}
