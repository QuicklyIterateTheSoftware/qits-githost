package eu.wohlben.qits.githost.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.jboss.logging.Logger;

/**
 * Turns one push into the events it means. The whole of the mapping, with no bus, no CDI and no
 * configuration in it — which is what lets the interesting half be tested by driving a real
 * repository rather than a container.
 *
 * <p>Four rules decide what comes out:
 *
 * <ul>
 *   <li><b>Only commands that ended {@code OK}.</b> A refused ref did not move; announcing it would
 *       have every consumer act on a push this host rejected. {@link ReceiveCommand#getResult()} is
 *       already final by the time a post-receive hook runs, so this is a read and not a guess.
 *   <li><b>Branches and tags, and nothing else.</b> {@code refs/heads/*} and {@code refs/tags/*}
 *       are the two namespaces the platform has a vocabulary for. A push that moves {@code
 *       refs/notes/*} or a ref of its own is silent rather than mapped onto the nearest word.
 *   <li><b>Delete is its own event.</b> The HTTP fan-out this replaces skipped deletions entirely,
 *       so a merged branch disappeared with nothing said.
 *   <li><b>Metadata comes from the repository, not from the wire.</b> The pack is already stored
 *       when this runs, so the head commit and the tag object can simply be read — which is the
 *       half the old {@code {repoId, branch, oldSha, newSha}} body never carried and every consumer
 *       had to fetch back.
 * </ul>
 *
 * <p>A ref whose metadata cannot be read produces <b>no event</b> and one warning. That is the
 * conservative direction: an event with invented fields is worse than a missing one, and the miss
 * is visible in a log while a wrong author is not.
 *
 * <p>Every event carries the {@link Address} the push arrived on — the storage id it landed in plus,
 * when the pusher used the public scheme, the {@code (projectId, repoName)} it was addressed by.
 * Echoed and never resolved: this class asks nobody what a repository is called.
 */
final class PostReceiveEvents {

  private static final Logger LOG = Logger.getLogger(PostReceiveEvents.class);

  private PostReceiveEvents() {}

  /**
   * Where the push landed and how it was addressed.
   *
   * @param repoId the opaque storage id — always known, because it is what was written
   * @param projectId the project segment of the push url, {@code null} on the id-addressed scheme
   * @param repoName the repository segment of the push url, {@code null} for the same reason
   */
  record Address(String repoId, String projectId, String repoName) {}

  /**
   * The events for one push, in command order.
   *
   * @param receivedAt this host's clock when the push landed — every event's {@code occurredAt}, so
   *     one push produces one instant rather than a spread of them
   */
  static List<QitsEvent> of(
      String repoId,
      String projectId,
      String repoName,
      Repository repo,
      Collection<ReceiveCommand> commands,
      boolean suppressCi,
      Instant receivedAt) {
    Address address = new Address(repoId, projectId, repoName);
    List<QitsEvent> events = new ArrayList<>();
    try (RevWalk walk = new RevWalk(repo)) {
      for (ReceiveCommand command : commands) {
        QitsEvent event = eventFor(address, walk, command, suppressCi, receivedAt);
        if (event != null) {
          events.add(event);
        }
      }
    }
    return events;
  }

  private static QitsEvent eventFor(
      Address address,
      RevWalk walk,
      ReceiveCommand command,
      boolean suppressCi,
      Instant receivedAt) {
    if (command.getResult() != ReceiveCommand.Result.OK) {
      return null;
    }
    String ref = command.getRefName();
    boolean deleted = command.getType() == ReceiveCommand.Type.DELETE;
    try {
      if (ref.startsWith(Constants.R_HEADS)) {
        String branch = ref.substring(Constants.R_HEADS.length());
        return deleted
            ? new SCMDeleteBranch(
                address.repoId(),
                address.projectId(),
                address.repoName(),
                branch,
                command.getOldId().name(),
                receivedAt)
            : publishedCommit(address, walk, branch, command, suppressCi, receivedAt);
      }
      if (ref.startsWith(Constants.R_TAGS)) {
        String tagName = ref.substring(Constants.R_TAGS.length());
        return deleted
            ? new SCMDeleteTag(
                address.repoId(),
                address.projectId(),
                address.repoName(),
                tagName,
                command.getOldId().name(),
                receivedAt)
            : publishedTag(address, walk, tagName, command, receivedAt);
      }
      return null;
    } catch (Exception e) {
      LOG.warnf("no event for %s in %s: %s", ref, address.repoId(), e.toString());
      return null;
    }
  }

  /** The head commit's own facts, read out of the pack that just arrived. */
  private static SCMPublishCommit publishedCommit(
      Address address,
      RevWalk walk,
      String branch,
      ReceiveCommand command,
      boolean suppressCi,
      Instant receivedAt)
      throws Exception {
    RevCommit head = walk.parseCommit(command.getNewId());
    List<String> parents = new ArrayList<>(head.getParentCount());
    for (RevCommit parent : head.getParents()) {
      parents.add(parent.name());
    }
    PersonIdent author = head.getAuthorIdent();
    PersonIdent committer = head.getCommitterIdent();
    return new SCMPublishCommit(
        address.repoId(),
        address.projectId(),
        address.repoName(),
        branch,
        command.getOldId().name(),
        command.getNewId().name(),
        parents,
        author == null ? null : author.getName(),
        author == null ? null : author.getEmailAddress(),
        author == null ? null : author.getWhenAsInstant(),
        committer == null ? null : committer.getWhenAsInstant(),
        head.getFullMessage(),
        suppressCi,
        receivedAt);
  }

  /**
   * The tag, peeled. An annotated tag's ref names a tag OBJECT and the commit is what it peels to;
   * a lightweight tag's ref names the commit, so the two shas are equal and {@code annotated} says
   * so rather than leaving a consumer to compare them.
   */
  private static SCMPublishTag publishedTag(
      Address address, RevWalk walk, String tagName, ReceiveCommand command, Instant receivedAt)
      throws Exception {
    ObjectId id = command.getNewId();
    RevObject object = walk.parseAny(id);
    if (object instanceof RevTag tag) {
      PersonIdent tagger = tag.getTaggerIdent();
      return new SCMPublishTag(
          address.repoId(),
          address.projectId(),
          address.repoName(),
          tagName,
          id.name(),
          walk.peel(tag).getId().name(),
          tagger == null ? null : tagger.getName(),
          tagger == null ? null : tagger.getEmailAddress(),
          tag.getFullMessage(),
          true,
          receivedAt);
    }
    return new SCMPublishTag(
        address.repoId(),
        address.projectId(),
        address.repoName(),
        tagName,
        id.name(),
        id.name(),
        null,
        null,
        null,
        false,
        receivedAt);
  }
}
