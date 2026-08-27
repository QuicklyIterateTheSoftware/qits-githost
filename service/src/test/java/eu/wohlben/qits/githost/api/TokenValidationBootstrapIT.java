package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like the idp's {@code IdpPackagedSurfaceIT}, but
 * with the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove: {@code %test}
 * disables the tenant outright, so the shipped {@code quarkus.oidc.*} block (auth-server-url +
 * jwks-path against a real listener, audience enforcement, groups→roles mapping) is exercised
 * nowhere else. The far side is {@link MockIdp}, whose recordings make the interaction assertable
 * on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. The story
 * is browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-the-git-host-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-git-host";

  /**
   * Hands the launched artifact its config the way a deployment does — the generic resource
   * triples and the audience as the <b>variable names the shipped expressions read</b>, so the
   * expressions themselves stay under test (the idp IT's pattern: the overrides reach the launched
   * process as system properties, and expression expansion reads the whole config).
   *
   * <p>The databases are the same embedded postgres the surefire suite spawns, under IT-own names
   * so nothing is shared with the {@code @QuarkusTest} databases. The mock idp starts here — before
   * the application — via {@link MockIdp#ensureStarted()}, which parks its coordinates in system
   * properties: a test profile is instantiated in more than one classloader, and the property
   * table is the one thing every copy (and the story method's {@link MockIdp#attach()}) shares.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    static final String AUDIENCE = "dev-qits-githost";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      return Map.of(
          "QITS_RESOURCE_DB_URL", EmbeddedPg.url("githost_packaged_it"),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          "QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url("eventstream_packaged_it"),
          "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD,
          "QITS_AUTH_MACHINE_AUDIENCE", AUDIENCE,
          // the one seam this test moves: where the idp is. Runtime key, so the packaged artifact
          // is otherwise exactly what ships.
          "quarkus.oidc.auth-server-url", idp.baseUrl(),
          // dark outside a deployment, like %dev/%test — both are runtime keys
          "quarkus.otel.sdk.disabled", "true",
          "qits.eventstream.enabled", "false");
    }
  }

  @UserStory(
      value = "On start, the git host fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-githost must validate service bearers before any caller arrives:
      at startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery stays
      off, the path is configured — so the very first `git` request carrying a platform token
      is accepted.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note("qits-githost starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/githost/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented
    // any token at all.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.happened("qits-githost", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), the githost side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded git surface.
    String platformToken =
        idp.token()
            .subject("qits-ci")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get("/git")
        .then()
        .statusCode(200)
        .body("repositories", notNullValue());
    story.happened("a platform service", "qits-githost", "GET /git (Bearer, groups=[qits:system])")
        .as("git-served");
  }

  @UserStory(
      value = "A stranger's token never opens the git host",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given().header("Authorization", "Bearer " + strangersToken).get("/git").then().statusCode(401);
    story.happened("an impostor", "qits-githost", "GET /git (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get("/git")
        .then()
        .statusCode(401);
    story
        .happened("an impostor", "qits-githost", "GET /git (another service's audience) -> 401")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY, ACCEPTED_SLUG, "qits-githost", "qits-platform-idp", "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
