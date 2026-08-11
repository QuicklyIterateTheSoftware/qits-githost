package eu.wohlben.qits.githost;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Every postgresql datasource this service boots carries the three-line resilience block — the
 * driver that holds a connection request while postgres comes back, the validation that turns a dead
 * pooled connection into a fresh request, and the acquisition timeout that keeps the waiter alive
 * while the first two work. Miss one and the other two do less than they read as.
 *
 * <p><b>A {@code @QuarkusTest} rather than the plain JUnit test the library's README shows</b>, and
 * the difference is the point here: this deployable boots TWO postgresql datasources — its own and
 * the qits-eventstream outbox's, which arrives with the jar at ordinal 100 — so the configuration
 * the rule has to judge is the merged one the application actually starts with, not what a bare
 * config could reconstruct from the classpath.
 */
@QuarkusTest
public class DatasourceBaselineTest {

  @Test
  public void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
