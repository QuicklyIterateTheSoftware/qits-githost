package eu.wohlben.qits.githost.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A branch is gone: this repository no longer has {@code branch}, which last pointed at {@code
 * sha}.
 *
 * <p><b>New. A deletion reached nobody before.</b> The post-receive fan-out skipped every {@code
 * DELETE} command, so a merged feature branch disappeared silently and a consumer holding state per
 * branch — a run history, a mirror, a dashboard row — had no way to learn it should stop.
 *
 * <p>{@code sha} is the OLD tip: the commit the branch pointed at when the delete arrived. It is
 * the only thing left to name the branch by, and it is what lets a consumer tell "the branch I knew
 * about" from one that had already moved on.
 *
 * <p>There is no {@code suppressCi} here and that is deliberate: {@code -o qits.no-ci} is about not
 * starting a build for pushed work, and a deletion starts none.
 *
 * <p>{@code projectId} and {@code repoName} are the address the push arrived on, echoed and not
 * resolved, and null for a push on the internal {@code /git/<storageId>} scheme — see {@link
 * SCMPublishCommit} for the whole of that rule.
 */
public record SCMDeleteBranch(
    UUID eventId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String sha,
    Instant receivedAt)
    implements QitsEvent {

  public SCMDeleteBranch {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SCMDeleteBranch(
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String sha,
      Instant receivedAt) {
    this(null, repoId, projectId, repoName, branch, sha, receivedAt);
  }

  @Override
  public Instant occurredAt() {
    return receivedAt;
  }
}
