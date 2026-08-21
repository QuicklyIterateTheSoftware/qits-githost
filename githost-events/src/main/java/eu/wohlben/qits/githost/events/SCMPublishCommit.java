package eu.wohlben.qits.githost.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A branch moved: this repository's {@code branch} now points at {@code sha}, where it pointed at
 * {@code oldSha} before.
 *
 * <p>One event per successfully updated branch ref of a push, so a push carrying three branches
 * publishes three. A ref whose {@code ReceiveCommand} did not end {@code OK} publishes none — a
 * refused ref did not move, and announcing it would have every consumer act on a push the host
 * rejected.
 *
 * <p><b>It replaces the post-receive HTTP fan-out</b>, which delivered {@code {repoId, branch,
 * oldSha, newSha}} to qits-ci and qits-projects with an in-memory retry loop and a three-minute
 * window after which the event was simply lost. This one goes through the qits-eventstream outbox:
 * a consumer that was down while the push landed reads it back.
 *
 * <p><b>The head commit's metadata travels with it</b>, read through JGit from the pack that just
 * arrived. That is the half the HTTP event never carried, and every consumer had to fetch it back
 * out of the host: {@code parents} (the new head's parent shas, empty for a root commit), the
 * author's name and address, and the two timestamps git itself distinguishes — {@code authoredAt}
 * (when the work was written) and {@code committedAt} (when this commit object was made, which a
 * rebase or an amend moves and the other does not).
 *
 * <p><b>{@code suppressCi} absorbs {@code -o qits.no-ci}</b> rather than suppressing the event.
 * The old notifier decided for its two consumers — it skipped the CI POST and sent the projects one
 * — which meant the option's meaning lived in the publisher and no third consumer could ever have
 * an opinion. Here the push option is a FACT ON THE EVENT: a run engine skips it, a backup trigger
 * ignores it, and neither has to be known here. An event is not a command.
 *
 * <p><b>{@code occurredAt} is {@code receivedAt}</b> — when this host finished taking the push, not
 * when the commit was authored or made. Those two are the pusher's clock and are in the payload
 * where a consumer can read them; the event log is ordered by when the platform learned.
 *
 * <p><b>{@code projectId} and {@code repoName} are the address the push arrived on</b>, echoed
 * rather than resolved. The public clone url is {@code /git/<projectId>/<repoName>}, so the route
 * already holds both coordinates when it announces — the git host looks nothing up, stores no name
 * and learns no domain. Both are <b>null</b> for a push on the internal {@code /git/<storageId>}
 * scheme (qits-projects' own mirror syncs): a consumer that needs a name ignores those events, and
 * one written against the older shape reads the payload as JSON and sees two keys it does not know.
 *
 * <p>{@code repoId} stays what it always was — the opaque storage id — and is never displayed.
 *
 * <p>{@code eventId} is a component and is generated when absent, which gives the idempotent {@code
 * PUT} the stable key a retry rests on. It never reaches the payload: {@code CanonicalJson}
 * excludes everything {@link QitsEvent} declares.
 */
public record SCMPublishCommit(
    UUID eventId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String oldSha,
    String sha,
    List<String> parents,
    String authorName,
    String authorEmail,
    Instant authoredAt,
    Instant committedAt,
    String message,
    boolean suppressCi,
    Instant receivedAt)
    implements QitsEvent {

  public SCMPublishCommit {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    // An empty list rather than null, so a root commit's payload reads `"parents":[]` instead of
    // omitting the key — the difference between "no parents" and "this publisher did not say".
    parents = parents == null ? List.of() : List.copyOf(parents);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SCMPublishCommit(
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String oldSha,
      String sha,
      List<String> parents,
      String authorName,
      String authorEmail,
      Instant authoredAt,
      Instant committedAt,
      String message,
      boolean suppressCi,
      Instant receivedAt) {
    this(
        null,
        repoId,
        projectId,
        repoName,
        branch,
        oldSha,
        sha,
        parents,
        authorName,
        authorEmail,
        authoredAt,
        committedAt,
        message,
        suppressCi,
        receivedAt);
  }

  @Override
  public Instant occurredAt() {
    return receivedAt;
  }
}
