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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /artifacts/git} on a host that serves nothing yet, against the {@code file} backend.
 *
 * <p>A class rather than a case in {@link GitHostSuite}, for the reason {@link GitHostNoCiOptionTest}
 * is one: an empty host is a process configuration. The shared data dir is {@code
 * target/githost-test-repos}, which every other class in this package writes into and which outlives
 * the run, so "empty" cannot be arranged inside the suite — it has to be a {@code @TestProfile} with
 * a data dir of its own.
 *
 * <p>That directory is never created, which is the case worth having: a deployment reads this way
 * before it is ever provisioned, and an absent data dir must be a host with no repositories rather
 * than a 500.
 *
 * <p>The body is asserted as an exact string. The field name is a cross-repo contract — qits-ci
 * reads {@code repositories} — and an empty host is the one answer where a typo'd or absent field
 * still looks plausible to a JSON path assertion.
 */
@QuarkusTest
@TestProfile(GitHostListingEmptyTest.FreshDataDir.class)
public class GitHostListingEmptyTest {

  public static class FreshDataDir implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.repositories.data-dir", "target/githost-empty-host/" + UUID.randomUUID());
    }
  }

  @Inject GitRepositoryBackend backend;

  @Test
  public void theSuiteIsRunningAgainstTheFileBackend() {
    assertEquals("file", backend.provider().name());
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
