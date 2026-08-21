package eu.wohlben.qits.githost.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.githost.ScmAnnouncer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.jboss.logging.Logger;

/**
 * The shipped {@link ScmAnnouncer}: it turns a landed push into {@code SCMPublishCommit}, {@code
 * SCMPublishTag}, {@code SCMDeleteBranch} and {@code SCMDeleteTag}, and hands each to {@link
 * QitsEventBus}. The producing end of this service's event-bus wiring, and the whole replacement for
 * {@code PostReceiveNotifier}.
 *
 * <p><b>What the replacement buys.</b> The notifier POSTed to two configured urls, retried in
 * memory for about three minutes and then logged the loss at WARN — a window the platform lost
 * events in twice, both times during a cutover in which a consumer was being redeployed. The bus
 * attempts the idempotent {@code PUT} inline and, if that does not land, writes an outbox row a
 * scheduled sweeper owns from there. A consumer that was down reads the event back; nothing is
 * decided here about who cares.
 *
 * <p><b>It lives in {@code bus/} rather than beside the routes</b>, exactly as qits-ci's {@code
 * BuildSuccessfulAnnouncer} does: {@link eu.wohlben.qits.githost.GitHostRoutes} knows the port and
 * not the bus, so a build of this service without an event bus is a matter of removing this class.
 *
 * <p><b>The push's receive time is stamped once</b>, here, and every event of the push carries it.
 * A per-event {@code Instant.now()} would spread one push over a handful of microseconds and make
 * the log's order of a multi-ref push an artifact of iteration order.
 *
 * <p><b>It does not throw and it does not block on the network.</b> {@link QitsEventBus#publish}
 * never throws; the inline attempt is bounded by {@code qits.eventstream.publish-timeout} and
 * everything past it belongs to the outbox. What is left is a database write per event on the
 * unhappy path — which is why this is still the shape that must not grow a retry loop of its own.
 */
@ApplicationScoped
public class ScmEventAnnouncer implements ScmAnnouncer {

  private static final Logger LOG = Logger.getLogger(ScmEventAnnouncer.class);

  @Inject QitsEventBus bus;

  /**
   * Runs on a Vert.x worker thread inside {@code ReceivePack.receive}, with no request context
   * bound — the same shape {@code CatalogRepository} documents. The outbox opens its own
   * transaction; this is what gives it a context to open it in. Inside a {@code @QuarkusTest} the
   * context is already active and the annotation is a no-op.
   */
  @Override
  @ActivateRequestContext
  public void onPostReceive(
      String repoId,
      String projectId,
      String repoName,
      Repository repo,
      Collection<ReceiveCommand> commands,
      boolean suppressCi) {
    Instant receivedAt = Instant.now();
    List<QitsEvent> events =
        PostReceiveEvents.of(repoId, projectId, repoName, repo, commands, suppressCi, receivedAt);
    for (QitsEvent event : events) {
      bus.publish(event);
    }
    LOG.debugf("push to %s announced as %d event(s)", repoId, events.size());
  }
}
