package eu.wohlben.qits.githost;

import java.util.Collection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * Port: something that is told about a push after its refs have landed. The seam between the wire
 * protocol and the platform, and the replacement for the {@code PostReceiveNotifier} that used to
 * POST to two hard-coded urls.
 *
 * <p>One implementation ships — {@code bus.ScmEventAnnouncer}, which publishes the {@code
 * githost-events} vocabulary through {@code QitsEventBus} — and <b>zero is a supported
 * configuration</b>: a deployment with no announcer serves git and announces nothing. The port stays
 * a port because it is what keeps "there is a bus" out of {@link GitHostRoutes}.
 *
 * <p><b>Three rules an implementation must keep</b>, all of them consequences of where it is
 * called:
 *
 * <ul>
 *   <li><b>Do not throw.</b> This runs inside {@code ReceivePack.receive}, after the ref updates
 *       have already been applied. The push has succeeded by then, so a failure here must never
 *       turn it into one. {@link GitHostRoutes} catches anything that escapes, and that is a
 *       backstop rather than a licence.
 *   <li><b>Do not block for long.</b> The push response has not been written yet, so every
 *       millisecond spent here is latency the pusher sees. Reading the repository is expected —
 *       that is what the {@code repo} argument is for — and waiting on a network is not.
 *   <li><b>Read the repository, do not write it.</b> {@code repo} is open and holds the objects the
 *       push just delivered; it is closed by the route as soon as {@code receive} returns, so
 *       nothing may hold on to it.
 * </ul>
 *
 * @see GitHostRoutes
 */
public interface ScmAnnouncer {

  /**
   * A push landed on {@code repoId}.
   *
   * @param repo the repository, open and readable, including the objects this push delivered
   * @param commands every command of the push, <b>including the ones that failed</b> — an
   *     implementation filters on {@link ReceiveCommand#getResult()} itself, because a refused ref
   *     did not move and must not be announced
   * @param suppressCi whether the push carried {@code -o qits.no-ci}. It is a fact to pass on, not
   *     an instruction to act on: what a consumer does about it is the consumer's decision.
   */
  void onPostReceive(
      String repoId, Repository repo, Collection<ReceiveCommand> commands, boolean suppressCi);
}
