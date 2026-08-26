package eu.wohlben.qits.githost.loc;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.githost.GitRepositoryProvider;
import eu.wohlben.qits.githost.persistence.RepositoryLocStore;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jboss.logging.Logger;

/**
 * Computes lines-of-code summaries off the push path, so the first browse after a push finds the
 * memo already written.
 *
 * <p><b>Why a hand-rolled single thread and not the scheduler or the bus.</b> The work arrives from
 * {@link LocAnnouncer}, which runs inside {@code ReceivePack.receive} and must neither block nor
 * touch the repository after the route closes it — so the announcer hands over nothing but ids, and
 * this class opens the repository again on its own time. One thread is the right width: a scan is
 * seconds at worst on this platform's repositories, pushes are not frequent, and a second scan of
 * the same commit would only race the first for the same insert. The scheduler stays out of it
 * because there is nothing periodic here, and the event bus because the memo is this service's own
 * private cache, not something the platform should hear about.
 *
 * <p><b>Losing work is fine.</b> The queue is in memory; a restart drops it, and the browse
 * endpoint computes on a memo miss anyway — which also covers every commit that predates this
 * feature. The precompute is a latency gift, not a correctness dependency.
 */
@ApplicationScoped
public class LocIndexer {

  private static final Logger LOG = Logger.getLogger(LocIndexer.class);

  @Inject GitRepositoryProvider repositories;
  @Inject RepositoryLocStore store;
  @Inject ObjectMapper mapper;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "loc-indexer");
            thread.setDaemon(true);
            return thread;
          });

  /** Hands one commit to the worker and returns immediately. Never throws. */
  public void enqueue(String repoId, String commitSha) {
    try {
      worker.execute(() -> index(repoId, commitSha));
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not enqueue the loc scan of %s@%s", repoId, commitSha);
    }
  }

  private void index(String repoId, String commitSha) {
    try {
      if (store.exists(repoId, commitSha)) {
        return; // an earlier push or an eager browser got here first
      }
      try (Repository repo = repositories.open(repoId)) {
        if (repo == null) {
          return; // deleted between the push and now; nothing to summarize
        }
        RevCommit commit;
        try (RevWalk walk = new RevWalk(repo)) {
          commit = walk.parseCommit(repo.resolve(commitSha));
        }
        List<LanguageLoc> languages = RepositoryLocScanner.scan(repo, commit);
        store.saveQuietly(
            repoId, commit.name(), mapper.writeValueAsString(new LocResponse(commit.name(), languages)));
      }
    } catch (Exception e) {
      // The browse endpoint rescans on a miss, so a failed precompute costs latency, not answers.
      LOG.warnf(e, "loc scan of %s@%s failed; the first browse will compute it", repoId, commitSha);
    }
  }

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }
}
