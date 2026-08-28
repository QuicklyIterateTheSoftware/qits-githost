package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.persistence.CatalogRepository;
import eu.wohlben.qits.githost.storage.PackDescription;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The three answers a lookup can end in: <b>absent is 404, a database that comes back is served, and
 * one that stays gone is 500</b>.
 *
 * <p>All three in one class because they are one contract. A catalog read that fails used to be
 * caught in {@code DfsGitRepositoryProvider.open}, logged at debug and returned as null —
 * indistinguishable from an id the host does not serve. It cost a platform bootstrap: postgres was
 * cut over mid-run, every connection pool was severed, and the push that arrived one second later
 * was told the repository did not exist. It did; the same url answered 200 minutes afterwards.
 *
 * <p><b>Patience precedes the honest failure; it does not replace it.</b> The existence check is
 * wrapped in {@code DbRetry}, so a connection lost mid-flight costs the request a pause and then
 * succeeds. What is under the deadline is served, what outlives it is still a 500, and neither is
 * ever a 404.
 *
 * <p>The failure is installed as a {@link QuarkusMock} over {@link CatalogRepository}, the one bean
 * between the repository and the database. Mocking it rather than breaking the datasource keeps the
 * fault to a single test: a datasource that cannot connect would take the whole process with it. The
 * deadline is shortened for the suite (see {@code src/test/resources/application.properties}), so
 * the test that proves the give-up does not wait out a production cutover.
 */
@QuarkusTest
public class GitHostCatalogUnavailableTest {

  @Inject GitRepositoryProvider repositories;

  /** The real catalog, read through before any mock is installed over it. */
  @Inject CatalogRepository catalog;

  /**
   * PUT THE REAL CATALOG BACK, after every method. {@code QuarkusMock.installMockForType} inside a
   * test method installs for the rest of the run, not the rest of the method — and a severed
   * catalog that outlives this class turns some later class's requests into 403s/500s that read
   * like that class's own bug. That was the {@code GitHostStorageClientTest} "flake": three
   * consecutive CI runs red on its first {@code GET /git}, every request underneath throwing THIS
   * class's {@code connectionLost()} (2026-08-28) — which order the suite ran in was the whole
   * probability. {@code ClientProxy.unwrap}, because installing the injected proxy AS the mock
   * would route the proxy to itself.
   */
  @AfterEach
  void restoreTheRealCatalog() {
    QuarkusMock.installMockForType(ClientProxy.unwrap(catalog), CatalogRepository.class);
  }

  /**
   * What a severed pool throws: unchecked, like Hibernate's {@code JDBCConnectionException}, over a
   * SQLState {@code 08} cause. The cause is what makes it a <b>connection</b> failure rather than a
   * failed statement, and therefore what {@code DbRetry} agrees to wait on — a business failure with
   * the same wording is rethrown at once, deliberately.
   */
  static IllegalStateException connectionLost() {
    return new IllegalStateException(
        "connection pool severed by a database cutover",
        new SQLTransientConnectionException("the connection attempt failed", "08001"));
  }

  /** A catalog whose connection pool is gone and stays gone. */
  public static class SeveredCatalog extends CatalogRepository {

    @Override
    public List<PackDescription> list(String repositoryId) {
      throw connectionLost();
    }
  }

  /**
   * A catalog that loses its connection once and answers the next time — the cutover as a request
   * straddling it sees it.
   *
   * <p>It replays an answer captured from the real catalog rather than reading the database again:
   * a {@code QuarkusMock} instance is constructed by hand, so the {@code @ActivateRequestContext}
   * that a real read needs is not around it, and what this test is about is the retry, not the
   * query.
   */
  public static class FlakyCatalog extends CatalogRepository {

    private final List<PackDescription> answer;
    private final AtomicInteger reads = new AtomicInteger();

    public FlakyCatalog(List<PackDescription> answer) {
      this.answer = answer;
    }

    @Override
    public List<PackDescription> list(String repositoryId) {
      if (reads.incrementAndGet() == 1) {
        throw connectionLost();
      }
      return answer;
    }

    int reads() {
      return reads.get();
    }
  }

  @Test
  public void aCatalogThatCannotAnswerIs5xx() {
    QuarkusMock.installMockForType(new SeveredCatalog(), CatalogRepository.class);
    given()
        .when()
        .get("/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
  }

  @Test
  public void aCatalogThatComesBackWithinTheDeadlineIsWaitedFor() throws Exception {
    // The incident's own request shape, with the outage short enough to survive: a repository that
    // exists, whose first read is severed mid-flight. Held, then served — not a 500 and above all
    // not a 404.
    String repoId = GitHostFixture.emptyOrigin(repositories);
    FlakyCatalog flaky = new FlakyCatalog(catalog.list(repoId));
    QuarkusMock.installMockForType(flaky, CatalogRepository.class);

    given()
        .when()
        .get("/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    assertTrue(flaky.reads() > 1, "the severed read was not retried at all");
  }

  @Test
  public void aRepositoryTheCatalogSaysIsAbsentIsStill404() {
    // The other half. Without it, throwing on every failure could hide behind a route that 500s
    // for absence too — and a clone of a typo'd id has to stay a 404.
    given()
        .when()
        .get("/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }
}
