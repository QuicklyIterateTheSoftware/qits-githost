package eu.wohlben.qits.githost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for everything one post-receive delivery touches and this repo does not contain:
 * qits-ci's event intake, qits-projects' event intake, and qits-platform-idp's token endpoint the
 * bearer for ci comes from. None exists in this repo, and the clone-alone rule says the suite may
 * reach none of them over a network.
 *
 * <p>One server with three contexts rather than one per consumer, so a test can assert which intake
 * was hit — the whole point of the fan-out is that the two counts differ under {@code -o
 * qits.no-ci}.
 *
 * <p>Either intake can be told to <b>refuse</b> its next few requests, which is how {@link
 * PostReceiveRetryTest} plays the outage the retry exists for. So each intake counts two things: the
 * requests it saw (attempts, refused ones included) and the ones it took (deliveries).
 *
 * <p><b>Everything crosses through system properties</b>, and that is not laziness. Quarkus builds a
 * {@code QuarkusTestProfile} in two classloaders — the JUnit one, whose config overrides reach the
 * application, and the Quarkus runtime one, where the test class itself lives — so a static field
 * here exists twice and the test would read the copy the application never wrote to. System
 * properties are the one namespace both loaders share, so the server is started once, its URL is
 * published there, and what it observed is published back the same way. See {@code StubNpmRegistry},
 * which pays for the same problem with a control endpoint.
 */
final class StubIntake {

  /** Where the server listens. Set by whichever classloader starts it first. */
  private static final String BASE_URL = "qits.test.intake.base-url";

  /** The Authorization header of the last ci <i>attempt</i>, or absent when it carried none. */
  static final String LAST_AUTHORIZATION = "qits.test.intake.ci.authorization";

  /** How many events each intake has taken, so a test can wait for one. */
  private static final String CI_DELIVERIES = "qits.test.intake.ci.deliveries";

  private static final String PROJECTS_DELIVERIES = "qits.test.intake.projects.deliveries";

  /** How many requests each intake saw, including the ones it refused. */
  private static final String CI_ATTEMPTS = "qits.test.intake.ci.attempts";

  private static final String PROJECTS_ATTEMPTS = "qits.test.intake.projects.attempts";

  /** How many further requests each intake refuses before it starts taking them again. */
  private static final String CI_REFUSALS = "qits.test.intake.ci.refusals";

  private static final String PROJECTS_REFUSALS = "qits.test.intake.projects.refusals";

  private static HttpServer server;

  /** The intake url ci's events are posted to. Starts the server on first use. */
  static synchronized String ciIntakeUrl() {
    return baseUrl() + "/ci/api/events/post-receive";
  }

  /** The intake url projects' events are posted to — the backup trigger. */
  static synchronized String projectsIntakeUrl() {
    return baseUrl() + "/projects/api/events/post-receive";
  }

  /** The auth-server-url the oidc client fetches its token from; it appends {@code /token}. */
  static synchronized String idpUrl() {
    return baseUrl() + "/idp";
  }

  /** The access token this stub idp hands out. Opaque to the client, which never inspects it. */
  static final String ACCESS_TOKEN = "stub-access-token-for-qits-ci";

  /** Forgets what the last run saw, so one test's delivery is not another's. */
  static void reset() {
    System.clearProperty(LAST_AUTHORIZATION);
    System.setProperty(CI_DELIVERIES, "0");
    System.setProperty(PROJECTS_DELIVERIES, "0");
    System.setProperty(CI_ATTEMPTS, "0");
    System.setProperty(PROJECTS_ATTEMPTS, "0");
    System.setProperty(CI_REFUSALS, "0");
    System.setProperty(PROJECTS_REFUSALS, "0");
  }

  static int ciDeliveries() {
    return count(CI_DELIVERIES);
  }

  static int projectsDeliveries() {
    return count(PROJECTS_DELIVERIES);
  }

  static int ciAttempts() {
    return count(CI_ATTEMPTS);
  }

  static int projectsAttempts() {
    return count(PROJECTS_ATTEMPTS);
  }

  /** Plays a consumer that is down: the next {@code requests} answer 503, later ones are taken. */
  static void ciRefusesNext(int requests) {
    System.setProperty(CI_REFUSALS, String.valueOf(requests));
  }

  static void projectsRefusesNext(int requests) {
    System.setProperty(PROJECTS_REFUSALS, String.valueOf(requests));
  }

  static String lastAuthorization() {
    return System.getProperty(LAST_AUTHORIZATION);
  }

  /**
   * The notifier is fire-and-forget, so an assertion has to wait for the POST rather than it. Both
   * waits are the same 5 s ceiling and both return as soon as the count moves.
   */
  static boolean awaitCiDelivery() throws InterruptedException {
    return await(CI_DELIVERIES);
  }

  static boolean awaitProjectsDelivery() throws InterruptedException {
    return await(PROJECTS_DELIVERIES);
  }

  /**
   * How long to give a delivery that must NOT arrive. Nothing can prove a negative here, so a test
   * asserting zero waits this long first and then reads the count.
   */
  static void awaitSpuriousDelivery() throws InterruptedException {
    Thread.sleep(300);
  }

  /** Waits for an intake to have seen this many requests, refused ones included. */
  static boolean awaitCiAttempts(int wanted) throws InterruptedException {
    return awaitAtLeast(CI_ATTEMPTS, wanted);
  }

  static boolean awaitProjectsAttempts(int wanted) throws InterruptedException {
    return awaitAtLeast(PROJECTS_ATTEMPTS, wanted);
  }

  private static boolean await(String counter) throws InterruptedException {
    return awaitAtLeast(counter, 1);
  }

  private static boolean awaitAtLeast(String counter, int wanted) throws InterruptedException {
    for (int attempt = 0; attempt < 50; attempt++) {
      if (count(counter) >= wanted) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  private static int count(String counter) {
    return Integer.parseInt(System.getProperty(counter, "0"));
  }

  private static synchronized String baseUrl() {
    String published = System.getProperty(BASE_URL);
    if (published != null) {
      return published;
    }
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/ci/api/events/post-receive", StubIntake::recordCiDelivery);
      server.createContext("/projects/api/events/post-receive", StubIntake::recordProjectsDelivery);
      server.createContext("/idp/token", StubIntake::issueToken);
      server.start();
      published = "http://127.0.0.1:" + server.getAddress().getPort();
      System.setProperty(BASE_URL, published);
      return published;
    } catch (Exception e) {
      throw new IllegalStateException("stub intake", e);
    }
  }

  private static void recordCiDelivery(HttpExchange exchange) throws java.io.IOException {
    bump(CI_ATTEMPTS);
    // Read on every attempt, not only an accepted one: the bearer is fetched again per attempt, so
    // a refused attempt's credential is a thing a test may want to look at.
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null) {
      System.clearProperty(LAST_AUTHORIZATION);
    } else {
      System.setProperty(LAST_AUTHORIZATION, authorization);
    }
    if (refused(exchange, CI_REFUSALS)) {
      return;
    }
    accept(exchange, CI_DELIVERIES);
  }

  private static void recordProjectsDelivery(HttpExchange exchange) throws java.io.IOException {
    bump(PROJECTS_ATTEMPTS);
    if (refused(exchange, PROJECTS_REFUSALS)) {
      return;
    }
    accept(exchange, PROJECTS_DELIVERIES);
  }

  /** Answers 503 while refusals are owed, counting one off. 503 is what a severed pool gives. */
  private static boolean refused(HttpExchange exchange, String refusals) throws java.io.IOException {
    int owed = count(refusals);
    if (owed <= 0) {
      return false;
    }
    System.setProperty(refusals, String.valueOf(owed - 1));
    exchange.sendResponseHeaders(503, -1);
    exchange.close();
    return true;
  }

  private static void accept(HttpExchange exchange, String counter) throws java.io.IOException {
    bump(counter);
    exchange.sendResponseHeaders(202, -1);
    exchange.close();
  }

  private static void bump(String counter) {
    System.setProperty(counter, String.valueOf(count(counter) + 1));
  }

  private static void issueToken(HttpExchange exchange) throws java.io.IOException {
    byte[] body =
        ("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
    exchange.close();
  }

  private StubIntake() {}
}
