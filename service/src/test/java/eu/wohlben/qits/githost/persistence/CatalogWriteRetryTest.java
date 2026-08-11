package eu.wohlben.qits.githost.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.githost.storage.PackDescription;
import eu.wohlben.qits.githost.storage.PackFile;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The write half of the catalog's patience: <b>a pack commit severed mid-transaction is run again
 * and leaves exactly one row, and a failure that is not a connection loss is not run again at
 * all</b>.
 *
 * <p>This is the last database step of a push. The pack's bytes are already promoted into the blob
 * store by the time it runs, so a connection lost here is the difference between a push that
 * survives a postgres cutover and one that wrote a pack it can never list. {@code
 * DbRetry.runInNewTx} is what closes it, and the two tests here are its two halves — the retry, and
 * the restraint that makes the retry safe.
 *
 * <p><b>Why the fault is installed under {@code commit} rather than over it.</b> {@code
 * GitHostCatalogUnavailableTest} severs a read by overriding the public method, because the read's
 * retry sits outside it in {@code DfsGitRepositoryProvider}. A write's retry sits <i>inside</i>
 * {@code commit} — it owns the transaction, which is the only position from which "this certainly
 * did not commit" is knowable — so a fault installed over {@code commit} would never be reached by
 * the loop. {@link CatalogRepository#writePacks} is the transaction's body and is where these
 * subclasses cut in.
 *
 * <p>Both faulty catalogs run their real write <b>first</b> and fail afterwards. That is the shape
 * that has something to prove: the rows reach the database, the transaction rolls back, and the next
 * attempt writes them again. A subclass that threw before delegating would prove only that an
 * exception is counted.
 */
@QuarkusTest
public class CatalogWriteRetryTest {

  /** The real catalog, for reading back what the faulty ones left behind. */
  @Inject CatalogRepository catalog;

  /** The suite's shortened deadline (see {@code src/test/resources/application.properties}). */
  @ConfigProperty(name = "qits.githost.db-retry-deadline")
  Duration deadline;

  /**
   * What a severed pool throws, spelled exactly as {@code GitHostCatalogUnavailableTest} spells it:
   * unchecked, over a SQLState {@code 08} cause. The cause is what makes it a <b>connection</b>
   * failure rather than a failed statement, and therefore what the retry agrees to wait on.
   */
  static IllegalStateException connectionLost() {
    return new IllegalStateException(
        "connection pool severed by a database cutover",
        new SQLTransientConnectionException("the connection attempt failed", "08001"));
  }

  /** A catalog whose write lands its statements and then loses the connection, once. */
  static final class SeveredWrite extends CatalogRepository {

    private final AtomicInteger attempts = new AtomicInteger();

    SeveredWrite(Duration deadline) {
      this.dbRetryDeadline = deadline;
    }

    @Override
    void writePacks(
        String repositoryId, Collection<PackDescription> add, Collection<PackDescription> remove) {
      super.writePacks(repositoryId, add, remove);
      if (attempts.incrementAndGet() == 1) {
        throw connectionLost();
      }
    }

    int attempts() {
      return attempts.get();
    }
  }

  /** A catalog whose write is refused for a reason a second attempt would meet again. */
  static final class RefusedWrite extends CatalogRepository {

    private final AtomicInteger attempts = new AtomicInteger();

    RefusedWrite(Duration deadline) {
      this.dbRetryDeadline = deadline;
    }

    @Override
    void writePacks(
        String repositoryId, Collection<PackDescription> add, Collection<PackDescription> remove) {
      super.writePacks(repositoryId, add, remove);
      attempts.incrementAndGet();
      throw new IllegalStateException("the pack catalog refused this write");
    }

    int attempts() {
      return attempts.get();
    }
  }

  @Test
  public void aWriteSeveredAfterItsStatementsIsRetriedAndLandsOnce() {
    String repoId = UUID.randomUUID().toString();
    SeveredWrite severed = new SeveredWrite(deadline);

    inRequestContext(() -> severed.commit(repoId, List.of(pack("pack-severed")), List.of()));

    assertEquals(2, severed.attempts(), "the severed write should have been run exactly twice");
    List<PackDescription> packs = catalog.list(repoId);
    assertEquals(1, packs.size(), "the retried write left the pack listed more than once");
    assertEquals("pack-severed", packs.get(0).packName());
    assertEquals(1, packs.get(0).files().size(), "the pack's file rows were duplicated too");
  }

  @Test
  public void aFailureThatIsNotAConnectionLossIsNotRetried() {
    String repoId = UUID.randomUUID().toString();
    RefusedWrite refused = new RefusedWrite(deadline);

    assertThrows(
        IllegalStateException.class,
        () ->
            inRequestContext(
                () -> refused.commit(repoId, List.of(pack("pack-refused")), List.of())));

    assertEquals(1, refused.attempts(), "a business failure was retried");
    assertTrue(catalog.list(repoId).isEmpty(), "the refused write was not rolled back");
  }

  /** One pack with one file — the smallest thing the catalog will store and read back. */
  private static PackDescription pack(String packName) {
    return new PackDescription(
        packName,
        "INSERT",
        1L,
        1L,
        0L,
        1L,
        1L,
        2,
        List.of(new PackFile("pack", "blob-" + packName, 42L, 0)));
  }

  /**
   * Runs work in a request context, because these catalogs are built with {@code new}: {@code
   * @ActivateRequestContext} is an interceptor on the CDI bean and does not travel to a
   * hand-constructed instance, and the entity manager under the write is request-scoped. An already
   * active context is used as it is, so nothing here nests one inside another.
   */
  private static void inRequestContext(Runnable work) {
    ManagedContext request = Arc.container().requestContext();
    if (request.isActive()) {
      work.run();
      return;
    }
    request.activate();
    try {
      work.run();
    } finally {
      request.terminate();
    }
  }
}
