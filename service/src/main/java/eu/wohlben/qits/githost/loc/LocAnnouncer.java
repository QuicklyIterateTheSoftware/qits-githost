package eu.wohlben.qits.githost.loc;

import eu.wohlben.qits.githost.ScmAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.jboss.logging.Logger;

/**
 * The {@link ScmAnnouncer} that warms the lines-of-code memo: every branch tip a push lands is
 * handed to {@link LocIndexer}, which scans it on its own thread.
 *
 * <p>It keeps the port's three rules by doing almost nothing here: it reads the commands, not the
 * repository, hands over plain strings, and returns — the indexer opens the repository again for
 * itself once the push is long finished. Only successful updates to {@code refs/heads/*} qualify: a
 * refused ref did not move, a deleted one has no tip, and a tag is browsed rarely enough to be the
 * miss path's business.
 */
@ApplicationScoped
public class LocAnnouncer implements ScmAnnouncer {

  private static final Logger LOG = Logger.getLogger(LocAnnouncer.class);

  @Inject LocIndexer indexer;

  @Override
  public void onPostReceive(
      String repoId,
      String projectId,
      String repoName,
      Repository repo,
      Collection<ReceiveCommand> commands,
      boolean suppressCi) {
    // A multi-branch push often lands one commit under several names; scan it once.
    Set<String> tips = new LinkedHashSet<>();
    for (ReceiveCommand command : commands) {
      if (command.getResult() == ReceiveCommand.Result.OK
          && command.getRefName().startsWith(Constants.R_HEADS)
          && !ObjectId.zeroId().equals(command.getNewId())) {
        tips.add(command.getNewId().name());
      }
    }
    for (String tip : tips) {
      indexer.enqueue(repoId, tip);
    }
    if (!tips.isEmpty()) {
      LOG.debugf("push to %s queued %d commit(s) for loc scanning", repoId, tips.size());
    }
  }
}
