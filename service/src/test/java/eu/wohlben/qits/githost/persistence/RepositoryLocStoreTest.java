package eu.wohlben.qits.githost.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class RepositoryLocStoreTest {

  @Inject RepositoryLocStore store;

  @Test
  public void aSecondWriteOfTheSameCommitChangesNothing() {
    store.saveQuietly("loc-store-idempotent", sha(1), "{\"a\":1}");
    store.saveQuietly("loc-store-idempotent", sha(1), "{\"a\":1}");
    assertEquals("{\"a\":1}", store.find("loc-store-idempotent", sha(1)).orElseThrow());
    assertEquals(1, countOf("loc-store-idempotent"));
  }

  @Test
  public void everyWritePrunesTheRepositoryDownToTheNewestSummaries() {
    for (int i = 0; i < RepositoryLocStore.KEPT_PER_REPOSITORY + 5; i++) {
      store.saveQuietly("loc-store-pruned", sha(i), "{\"i\":" + i + "}");
    }
    assertEquals(RepositoryLocStore.KEPT_PER_REPOSITORY, countOf("loc-store-pruned"));
    assertFalse(store.exists("loc-store-pruned", sha(0)));
    assertTrue(store.exists("loc-store-pruned", sha(RepositoryLocStore.KEPT_PER_REPOSITORY + 4)));
  }

  @Test
  public void aCommitNobodyStoredAnswersEmpty() {
    assertTrue(store.find("loc-store-absent", sha(9)).isEmpty());
  }

  private static long countOf(String repositoryId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> GitRepositoryLoc.count("repositoryId", repositoryId));
  }

  private static String sha(int i) {
    return String.format("%040x", i);
  }
}
