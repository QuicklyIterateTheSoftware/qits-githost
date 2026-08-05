package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link GitHostListingEmptyTest} against the {@code dfs} backend — the empty host measured on the
 * storage engine that answers the question from rows rather than from a directory.
 *
 * <p>Emptiness is arranged differently for the same reason: this backend keeps no directory, so what
 * has to be private here is the <b>database</b>. The suite's H2 is one in-memory instance shared by
 * every class in the run, and {@code GitHostDfsTest} fills its {@code git_pack} table, so this
 * profile names an instance of its own. Flyway migrates it exactly as it does the shared one.
 */
@QuarkusTest
@TestProfile(GitHostListingEmptyDfsTest.FreshCatalog.class)
public class GitHostListingEmptyDfsTest {

  public static class FreshCatalog implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.repositories.git.storage", "dfs",
          "quarkus.datasource.artifacts.jdbc.url",
              "jdbc:h2:mem:artifacts-githost-empty;DB_CLOSE_DELAY=-1");
    }
  }

  @Inject GitRepositoryBackend backend;

  @Test
  public void theSuiteIsRunningAgainstTheDfsBackend() {
    // A profile that failed to apply would prove the file backend's answer a second time.
    assertEquals("dfs", backend.provider().name());
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
