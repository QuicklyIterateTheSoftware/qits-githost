package eu.wohlben.qits.githost.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.persistence.CatalogRepository;
import eu.wohlben.qits.githost.persistence.RepositoryProtectionStore;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /githost/api/repositories} — the SPA's catalogue read, and the two ways it can fail.
 *
 * <p>The failing half is the point of this class. A read this API cannot make is a <b>500</b>, never
 * an empty list: a page told "no repositories" shows an empty host, which is a plausible answer and
 * a wrong one, and nothing on the screen says the service could not ask. That is the same lesson as
 * {@code GitHostCatalogUnavailableTest}'s, one surface further out — a severed pool once made a live
 * repository answer 404 on the git routes (fixed in {@code fe26a6c}), and this resource is a second
 * place that mistake could be made.
 *
 * <p>Both stores are faulted with a {@link QuarkusMock}, rather than by breaking the datasource,
 * which would take the whole process with it.
 */
@QuarkusTest
public class RepositoriesResourceTest {

  static final String PATH = "/githost/api/repositories";

  @Inject GitRepositoryProvider repositories;

  /** Injected to WRITE an override, which is the only way to prove the field reports one. */
  @Inject RepositoryProtectionStore protections;

  /**
   * What a severed pool throws — unchecked, over a SQLState {@code 08} cause, the same shape {@code
   * GitHostCatalogUnavailableTest} builds. Spelled again here rather than shared, because that
   * class's helper is package-private to the git-route tests and this one is testing another
   * package.
   */
  static IllegalStateException connectionLost() {
    return new IllegalStateException(
        "connection pool severed by a database cutover",
        new SQLTransientConnectionException("the connection attempt failed", "08001"));
  }

  /** A pack catalog whose connection pool is gone and stays gone. */
  public static class SeveredCatalog extends CatalogRepository {
    @Override
    public List<String> repositoryIds() {
      throw connectionLost();
    }
  }

  /** A protection store whose connection pool is gone and stays gone. */
  public static class SeveredProtections extends RepositoryProtectionStore {
    @Override
    public Map<String, Boolean> protectionOverrides() {
      throw connectionLost();
    }
  }

  @Test
  public void everyRepositoryIsListedWithItsId() throws Exception {
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");

    JsonPath body =
        given()
            .when()
            .get(PATH)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .contentType(containsString("application/json"))
            .extract()
            .jsonPath();

    // A LIST OF RECORDS, not of strings — that is what separates this surface from `GET /git`, and
    // it is what lets a field be added here without touching a wire every clone url depends on.
    List<Map<String, ?>> rows = body.getList("repositories");
    assertThat(rows, everyItem(hasKey("id")));
    assertThat(body.getList("repositories.id", String.class), hasItem(repoId));

    // The repository just created carries no override row, so it shows the platform default —
    // false in the shipped config. Asserted on THIS row rather than on every row: the suite shares
    // one database across classes and another test's override is not this test's business.
    assertThat(rowFor(rows, repoId).get("protectDefaultBranch"), is(false));
  }

  @Test
  public void anOverrideRowIsWhatTheFieldReports() throws Exception {
    // The field is the EFFECTIVE answer — what a pusher would meet — not "is there a row". Both
    // halves have to be read for that, and this is the half the platform default hides.
    String repoId = UUID.randomUUID().toString();
    repositories.create(repoId, "main");
    protections.setProtectionOverride(repoId, true);

    List<Map<String, ?>> rows =
        given().when().get(PATH).then().statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .jsonPath()
            .getList("repositories");
    assertThat(rowFor(rows, repoId).get("protectDefaultBranch"), is(true));
  }

  /** The one row this test made, out of a listing that holds the whole suite's repositories. */
  private static Map<String, ?> rowFor(List<Map<String, ?>> rows, String repoId) {
    return rows.stream()
        .filter(row -> repoId.equals(row.get("id")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the listing does not carry " + repoId));
  }

  @Test
  public void aCatalogThatCannotAnswerIs5xx() {
    QuarkusMock.installMockForType(new SeveredCatalog(), CatalogRepository.class);
    given()
        .when()
        .get(PATH)
        .then()
        .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
  }

  @Test
  public void protectionOverridesThatCannotBeReadAre5xxToo() {
    // The second read, and it fails the same way on purpose. Softened to the platform default — the
    // fallback the push path uses, correctly — this would answer 200 with a guess in every row.
    QuarkusMock.installMockForType(new SeveredProtections(), RepositoryProtectionStore.class);
    given()
        .when()
        .get(PATH)
        .then()
        .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
  }
}
