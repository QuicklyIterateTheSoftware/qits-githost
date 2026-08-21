package eu.wohlben.qits.githost.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A tag is gone: this repository no longer has {@code tagName}, which last held {@code sha}.
 *
 * <p>The counterpart of {@link SCMPublishTag}, and new for the same reason — tags never left the
 * git host at all before this vocabulary existed.
 *
 * <p>{@code sha} is the OLD tip: what the ref held when the delete arrived. It is the ref's own
 * value rather than the commit it peeled to, because an annotated tag's object is what was removed
 * and a consumer matching this against a tag it recorded matches on that.
 *
 * <p>{@code projectId} and {@code repoName} are the address the push arrived on, echoed and not
 * resolved, and null for a push on the internal {@code /git/<storageId>} scheme — see {@link
 * SCMPublishCommit} for the whole of that rule.
 */
public record SCMDeleteTag(
    UUID eventId,
    String repoId,
    String projectId,
    String repoName,
    String tagName,
    String sha,
    Instant receivedAt)
    implements QitsEvent {

  public SCMDeleteTag {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SCMDeleteTag(
      String repoId,
      String projectId,
      String repoName,
      String tagName,
      String sha,
      Instant receivedAt) {
    this(null, repoId, projectId, repoName, tagName, sha, receivedAt);
  }

  @Override
  public Instant occurredAt() {
    return receivedAt;
  }
}
