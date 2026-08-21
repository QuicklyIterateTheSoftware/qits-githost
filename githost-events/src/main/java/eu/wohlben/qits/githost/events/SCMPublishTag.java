package eu.wohlben.qits.githost.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A tag appeared or moved: this repository now has {@code tagName} at {@code sha}.
 *
 * <p><b>New. Tags never left the git host before.</b> The post-receive fan-out filtered its
 * commands down to {@code refs/heads/*}, so a release tag — the one ref the platform's release flow
 * pushes beside the branch — reached nobody, and qits-projects' backup swept for them on its own
 * schedule instead. One event per created or updated tag ref of the push.
 *
 * <p><b>{@code sha} is what the ref holds; {@code targetSha} is the commit it means.</b> For an
 * annotated tag the ref names a tag OBJECT and {@code targetSha} is what it peels to — the two
 * differ, and a consumer that wants the commit wants the second. For a lightweight tag the ref
 * names the commit directly and the two are equal, which is what {@code annotated} says out loud
 * so a consumer never has to compare them to find out.
 *
 * <p>{@code taggerName}, {@code taggerEmail} and {@code message} come off the tag object and are
 * therefore <b>null for a lightweight tag</b>, which has none. A null field is omitted from the
 * canonical payload rather than written as an explicit null.
 *
 * <p>{@code projectId} and {@code repoName} are the address the push arrived on, echoed and not
 * resolved, and null for a push on the internal {@code /git/<storageId>} scheme — see {@link
 * SCMPublishCommit} for the whole of that rule.
 *
 * <p>{@code occurredAt} is {@code receivedAt}, the moment this host finished taking the push — see
 * {@link SCMPublishCommit} for why the log is ordered by the platform's clock and not the pusher's.
 */
public record SCMPublishTag(
    UUID eventId,
    String repoId,
    String projectId,
    String repoName,
    String tagName,
    String sha,
    String targetSha,
    String taggerName,
    String taggerEmail,
    String message,
    boolean annotated,
    Instant receivedAt)
    implements QitsEvent {

  public SCMPublishTag {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SCMPublishTag(
      String repoId,
      String projectId,
      String repoName,
      String tagName,
      String sha,
      String targetSha,
      String taggerName,
      String taggerEmail,
      String message,
      boolean annotated,
      Instant receivedAt) {
    this(
        null,
        repoId,
        projectId,
        repoName,
        tagName,
        sha,
        targetSha,
        taggerName,
        taggerEmail,
        message,
        annotated,
        receivedAt);
  }

  @Override
  public Instant occurredAt() {
    return receivedAt;
  }
}
