package eu.wohlben.qits.githost;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.githost.persistence.CatalogRepository;
import eu.wohlben.qits.githost.storage.PackDescription;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two answers a lookup can end in, side by side: <b>absent is 404, unavailable is 500</b>.
 *
 * <p>Both in one class because the pair is the contract. A catalog read that fails used to be caught
 * in {@code DfsGitRepositoryProvider.open}, logged at debug and returned as null — indistinguishable
 * from an id the host does not serve. It cost a platform bootstrap: postgres was cut over mid-run,
 * every connection pool was severed, and the push that arrived one second later was told the
 * repository did not exist. It did; the same url answered 200 minutes afterwards.
 *
 * <p>The failure is installed as a {@link QuarkusMock} over {@link CatalogRepository}, the one bean
 * between the repository and the database. Mocking it rather than breaking the datasource keeps the
 * fault to a single test: a datasource that cannot connect would take the whole process with it.
 */
@QuarkusTest
public class GitHostCatalogUnavailableTest {

  /**
   * A catalog whose connection pool is gone. Unchecked, like the {@code JDBCConnectionException}
   * Hibernate throws when the database goes away under an open pool.
   */
  public static class SeveredCatalog extends CatalogRepository {

    @Override
    public List<PackDescription> list(String repositoryId) {
      throw new IllegalStateException("connection pool severed by a database cutover");
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
