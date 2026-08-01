package eu.wohlben.qits.githost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for both ends of the post-receive delivery: qits-ci's event intake, and qits-idp's token
 * endpoint that the bearer for it comes from. Neither exists in this repo, and the clone-alone rule
 * says the suite may reach neither over a network.
 *
 * <p><b>Everything crosses through system properties</b>, and that is not laziness. Quarkus builds a
 * {@code QuarkusTestProfile} in two classloaders — the JUnit one, whose config overrides reach the
 * application, and the Quarkus runtime one, where the test class itself lives — so a static field
 * here exists twice and the test would read the copy the application never wrote to. System
 * properties are the one namespace both loaders share, so the server is started once, its URL is
 * published there, and what it observed is published back the same way. See {@code StubNpmRegistry},
 * which pays for the same problem with a control endpoint.
 */
final class StubCiIntake {

  /** Where the server listens. Set by whichever classloader starts it first. */
  private static final String BASE_URL = "qits.test.ci-intake.base-url";

  /** The Authorization header of the last delivery, or absent when it carried none. */
  static final String LAST_AUTHORIZATION = "qits.test.ci-intake.authorization";

  /** How many events have been delivered, so a test can wait for one. */
  static final String DELIVERIES = "qits.test.ci-intake.deliveries";

  private static HttpServer server;

  /** The intake url the notifier posts events to. Starts the server on first use. */
  static synchronized String intakeUrl() {
    return baseUrl() + "/ci/api/events/post-receive";
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
    System.setProperty(DELIVERIES, "0");
  }

  static int deliveries() {
    return Integer.parseInt(System.getProperty(DELIVERIES, "0"));
  }

  static String lastAuthorization() {
    return System.getProperty(LAST_AUTHORIZATION);
  }

  private static synchronized String baseUrl() {
    String published = System.getProperty(BASE_URL);
    if (published != null) {
      return published;
    }
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/ci/api/events/post-receive", StubCiIntake::recordDelivery);
      server.createContext("/idp/token", StubCiIntake::issueToken);
      server.start();
      published = "http://127.0.0.1:" + server.getAddress().getPort();
      System.setProperty(BASE_URL, published);
      return published;
    } catch (Exception e) {
      throw new IllegalStateException("stub ci intake", e);
    }
  }

  private static void recordDelivery(HttpExchange exchange) throws java.io.IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if (authorization == null) {
      System.clearProperty(LAST_AUTHORIZATION);
    } else {
      System.setProperty(LAST_AUTHORIZATION, authorization);
    }
    System.setProperty(DELIVERIES, String.valueOf(deliveries() + 1));
    exchange.sendResponseHeaders(202, -1);
    exchange.close();
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

  private StubCiIntake() {}
}
