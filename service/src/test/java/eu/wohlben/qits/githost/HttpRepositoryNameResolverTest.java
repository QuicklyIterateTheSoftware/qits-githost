package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HTTP adapter on its own, as a plain JUnit test rather than a {@code @QuarkusTest}: under CDI
 * {@link FakeRepositoryNameResolver} overrides it (it carries {@code @DefaultBean}), so the only way
 * to exercise the real one is to build it and set its two fields. Upstream is an in-process {@link
 * HttpServer}, the {@code StubIntake} / {@code StubNpmRegistry} idiom — there is no network here, and
 * a test that reached a real qits-projects would pass or fail for reasons unrelated to this code.
 *
 * <p>Every failure case asserts {@code Optional.empty()} rather than an exception, because that is
 * the port's whole contract: {@code GitHostRoutes} has no exception clause, so a throw here would be
 * a 500 where a 404 is owed.
 */
class HttpRepositoryNameResolverTest {

  private HttpServer upstream;
  private String base;

  /** The last path the stub was asked for, so the composed url is asserted rather than assumed. */
  private final AtomicReference<String> lastPath = new AtomicReference<>();

  private final AtomicInteger requests = new AtomicInteger();

  /** What the stub answers next: a status and a body. */
  private int status = 200;

  private String body = "{\"repositoryId\":\"repo-1\"}";

  @BeforeEach
  void startUpstream() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    upstream.createContext("/", this::answer);
    upstream.start();
    base = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/projects/api/projects";
  }

  @AfterEach
  void stopUpstream() {
    upstream.stop(0);
  }

  private void answer(HttpExchange exchange) throws java.io.IOException {
    requests.incrementAndGet();
    lastPath.set(exchange.getRequestURI().getPath());
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  private HttpRepositoryNameResolver resolver(String url) {
    HttpRepositoryNameResolver resolver = new HttpRepositoryNameResolver();
    resolver.resolverUrl = Optional.ofNullable(url);
    resolver.objectMapper = new ObjectMapper();
    return resolver;
  }

  @Test
  void aConfiguredResolverAsksQitsProjectsAndReturnsTheId() {
    assertEquals(Optional.of("repo-1"), resolver(base).resolveRepositoryId("p-7", "testing-repo"));
    assertEquals(
        "/projects/api/projects/p-7/repositories/by-name/testing-repo",
        lastPath.get(),
        "the contract path, composed segment for segment");
  }

  @Test
  void aTrailingDotGitNeverReachesTheUrl() {
    // GitHostRoutes strips it first; this is the belt-and-braces strip, and it is what keeps a
    // caller that skipped that step from looking up a name qits-projects never registered.
    assertEquals(
        Optional.of("repo-1"), resolver(base).resolveRepositoryId("p-7", "testing-repo.git"));
    assertEquals("/projects/api/projects/p-7/repositories/by-name/testing-repo", lastPath.get());
  }

  @Test
  void anUnknownNameIsEmpty() {
    status = 404;
    body = "";
    assertEquals(Optional.empty(), resolver(base).resolveRepositoryId("p-7", "nope"));
  }

  @Test
  void aBrokenAnswerIsEmptyRatherThanAThrow() {
    status = 500;
    body = "not json at all";
    assertEquals(Optional.empty(), resolver(base).resolveRepositoryId("p-7", "testing-repo"));

    status = 200;
    body = "{\"unexpected\":true}";
    assertEquals(Optional.empty(), resolver(base).resolveRepositoryId("p-7", "testing-repo"));

    body = "}{ garbage";
    assertEquals(Optional.empty(), resolver(base).resolveRepositoryId("p-7", "testing-repo"));
  }

  @Test
  void anUnreachableUpstreamIsEmptyAndBounded() {
    long startedAt = System.nanoTime();
    assertEquals(
        Optional.empty(),
        resolver("http://localhost:1/projects/api/projects")
            .resolveRepositoryId("p-7", "testing-repo"));
    Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
    assertTrue(
        took.compareTo(HttpRepositoryNameResolver.REQUEST_TIMEOUT) <= 0,
        "an unreachable resolver must not outlast the request timeout, but took " + took);
  }

  @Test
  void withNoUrlConfiguredNothingIsDialled() {
    assertEquals(Optional.empty(), resolver(null).resolveRepositoryId("p-7", "testing-repo"));
    // The same for a configured-empty value, which SmallRye would hand over as absent anyway.
    assertEquals(Optional.empty(), resolver("  ").resolveRepositoryId("p-7", "testing-repo"));
    assertEquals(0, requests.get(), "an unconfigured resolver must make no request at all");
    assertNull(lastPath.get());
  }
}
