package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.stories.support.MockProjects;
import eu.wohlben.qits.githost.stories.support.StoryAccessLog;
import eu.wohlben.qits.githost.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b> — like the idp's {@code IdpPackagedSurfaceIT}, but
 * with the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove: {@code %test}
 * disables the tenant outright, so the shipped {@code quarkus.oidc.*} block (auth-server-url +
 * jwks-path against a real listener, audience enforcement, groups→roles mapping) is exercised
 * nowhere else. The far side is {@link MockIdp}, whose recordings make the interaction assertable
 * on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>, and the {@code authentication} category's whole
 * content: the proof doubles as documentation, emitted under {@code target/userstories/} with a
 * network diagram beside the steps. The diagram is <b>observed, never narrated</b> — {@link
 * NetworkTaps#restAssured(String)} taps what a story sends into this service, {@link MockIdp}'s
 * recordings supply what this service sent to the idp, and the framework drains both at story end.
 * A story method therefore asserts and notes; it draws nothing. The story is browserless (no
 * {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>The tap is the framework's now.</b> This class carried a hand-copied {@code
 * StoryNetworkFilter} — twenty lines four repositories each had a copy of — until qits-userflows
 * shipped {@link NetworkTaps}. The copy is deleted; the shipped tap takes the service name and
 * skips the same {@code /q/} segment, which is the right default here because this service's probe
 * root is {@code /githost/q} and the git wire at {@code /git} carries no such segment.
 *
 * <p><b>The profile below is the whole IT phase's.</b> Every story class in this module names it,
 * so failsafe launches the packaged process exactly once and the git stories under {@code
 * stories/} share this one's databases, idp and access log. That sharing is why the access-log tap
 * can attribute traffic at all — see {@link StoryAccessLog}.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a
 * cumulative source is attributed by a cursor, so traffic that happened before any story ran — the
 * startup JWKS fetch, which is the whole subject of the first story — lands in whichever story
 * drains <i>first</i>. Pinning the order is what keeps that the story it belongs to.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-the-git-host-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-git-host";

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = "qits-githost";

  /**
   * Hands the launched artifact its config the way a deployment does — the generic resource
   * triples and the audience as the <b>variable names the shipped expressions read</b>, so the
   * expressions themselves stay under test (the idp IT's pattern: the overrides reach the launched
   * process as system properties, and expression expansion reads the whole config).
   *
   * <p>The databases are the same embedded postgres the surefire suite spawns, under IT-own names
   * so nothing is shared with the {@code @QuarkusTest} databases. The two mocks start here — before
   * the application — via {@code ensureStarted()}, which parks their coordinates in system
   * properties: a test profile is instantiated in more than one classloader, and the property
   * table is the one thing every copy (and a story method's {@code attach()}) shares.
   *
   * <p><b>Three of the seams below exist for the git stories rather than for this class</b>, and
   * they live here because this is the profile every story class in the module names — one profile
   * means one launched process for the whole IT phase, which is what makes the access log a single
   * attributable recording:
   *
   * <ul>
   *   <li>{@code qits.projects.name-resolver-url} — without it the PUBLIC clone url
   *       ({@code /git/:projectId/:repoName}) 404s and only the internal storage scheme serves, so
   *       not one git story could clone anything. {@link MockProjects} is the far end.
   *   <li>the {@code quarkus.http.access-log.*} block — the git stories' only possible network
   *       tap, since their subject is a real {@code git} talking over a socket this JVM is not on.
   *   <li>{@code qits.githost.storage-client} stays UNSET, which is the shipped compat arm: the
   *       id-addressed scheme then opens to {@code qits:system} and the fixture can provision
   *       through it. Setting it is a different story than any told here.
   * </ul>
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    static final String AUDIENCE = "dev-qits-githost";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("QITS_RESOURCE_DB_URL", EmbeddedPg.url("githost_packaged_it"));
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url("eventstream_packaged_it"));
      overrides.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("QITS_AUTH_MACHINE_AUDIENCE", AUDIENCE);
      // the one seam this test moves: where the idp is. Runtime key, so the packaged artifact
      // is otherwise exactly what ships.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // Where (projectId, repoName) becomes a storage id — the key a real deployment must set or
      // it serves nothing anyone can clone. Runtime, and read per request: nothing is cached, so a
      // rename can never serve a stale id.
      overrides.put("qits.projects.name-resolver-url", MockProjects.resolverUrl());
      // dark outside a deployment, like %dev/%test — both are runtime keys
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      overrides.putAll(StoryAccessLog.configOverrides());
      return overrides;
    }
  }

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>The framework's RestAssured tap is the near side (what a story sends here). The idp is the
   * far side, registered as a <b>cumulative</b> source: the supplier hands over the mock's whole
   * request log every time it is asked and the framework remembers how much of it earlier stories
   * already consumed, so the startup fetch — recorded long before any story existed — is attributed
   * to the first story and to that one only. It is invoked lazily at story end, so registering it
   * here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    // Idempotent per service and installed from a story class's @BeforeAll, which is the only
    // place with a story border around it: RestAssured.filters is JVM-global for the whole
    // failsafe fork. The default skip (any /q/ segment) is right here — probes live at
    // /githost/q and the git wire at /git carries no such segment.
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
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
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note("qits-githost starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/githost/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented
    // any token at all. The edge itself is drained from the mock's recording; what is asserted here
    // is that it happened, and the note is the one thing the recording cannot carry — WHEN.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), the githost side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded git surface.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this
    // is what makes the observed edge read `a platform service -> qits-githost`.
    NetworkCapture.actor("a platform service");
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
    story
        .note("a platform service's bearer (aud=dev-qits-githost, groups=[qits:system]) is accepted")
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
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // Everything this story sends is an impostor's, so the actor is set once, up front.
    NetworkCapture.actor("an impostor");

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given().header("Authorization", "Bearer " + strangersToken).get("/git").then().statusCode(401);
    // Both refusals are the same edge — same actor, same route, same status — so the diagram
    // draws one arrow and the notes are what keep the two credentials distinguishable. That is
    // the right division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get("/git")
        .then()
        .statusCode(401);
    story
        .note("a token minted for another service's audience is refused just the same")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete now also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the filter, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", "a platform service", SERVICE, "GET /git -> 200");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "git-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", "an impostor", SERVICE, "GET /git -> 401");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
