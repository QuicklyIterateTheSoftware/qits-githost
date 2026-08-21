package eu.wohlben.qits.githost.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hook-to-event mapping, driven against a real repository and no container at all.
 *
 * <p>Plain JUnit on purpose: the mapping is where the interesting decisions are — which commands
 * become events, which namespace a ref belongs to, what a tag peels to — and every one of them is
 * decidable from a repository and a list of {@code ReceiveCommand}s. Putting it behind a
 * {@code @QuarkusTest} would make each case cost a boot and would prove the same thing.
 *
 * <p>{@link InMemoryRepository} is JGit's own DFS repository, so the objects these cases build are
 * read back through exactly the machinery a pushed pack is.
 */
class PostReceiveEventsTest {

  private static final Instant AUTHORED = Instant.parse("2026-08-10T09:00:00Z");
  private static final Instant COMMITTED = Instant.parse("2026-08-10T09:01:00Z");
  private static final Instant RECEIVED = Instant.parse("2026-08-10T09:02:03Z");

  /** The address the push arrived on, which every event of it echoes. */
  private static final String PROJECT = "qits";

  private static final String REPO_NAME = "testing-repo";

  private Repository repo;
  private ObjectInserter inserter;

  @BeforeEach
  void openRepository() {
    repo = new InMemoryRepository(new DfsRepositoryDescription("test"));
    inserter = repo.newObjectInserter();
  }

  @Test
  void aBranchUpdateBecomesOnePublishCommitCarryingTheHeadsOwnMetadata() throws Exception {
    ObjectId first = commit("first\n");
    ObjectId second = commit("second commit\n", first);

    List<QitsEvent> events = map(update("refs/heads/main", first, second), false);

    SCMPublishCommit event = assertInstanceOf(SCMPublishCommit.class, only(events));
    assertEquals("r", event.repoId());
    assertEquals("main", event.branch());
    assertEquals(first.name(), event.oldSha());
    assertEquals(second.name(), event.sha());
    assertEquals(List.of(first.name()), event.parents());
    assertEquals("Ada", event.authorName());
    assertEquals("ada@local", event.authorEmail());
    assertEquals(AUTHORED, event.authoredAt());
    assertEquals(COMMITTED, event.committedAt());
    assertEquals("second commit\n", event.message());
    assertFalse(event.suppressCi());
    assertEquals(RECEIVED, event.occurredAt());
  }

  @Test
  void theFirstPushCreatesTheBranchAndTheEventSaysSoWithTheZeroOldSha() throws Exception {
    ObjectId root = commit("seed\n");

    SCMPublishCommit event =
        assertInstanceOf(
            SCMPublishCommit.class,
            only(map(create("refs/heads/main", root), false)));

    assertEquals(ObjectId.zeroId().name(), event.oldSha());
    assertEquals(List.of(), event.parents(), "a root commit has no parents");
  }

  @Test
  void onePushCarryingThreeRefsBecomesThreeEventsInCommandOrder() throws Exception {
    ObjectId main = commit("main\n");
    ObjectId feature = commit("feature\n", main);
    ObjectId tag = annotatedTag("v1", main, "release");

    List<QitsEvent> events =
        map(
            List.of(
                create("refs/heads/main", main),
                create("refs/heads/feature/x", feature),
                create("refs/tags/v1", tag)),
            false);

    assertEquals(3, events.size());
    assertEquals("main", assertInstanceOf(SCMPublishCommit.class, events.get(0)).branch());
    assertEquals("feature/x", assertInstanceOf(SCMPublishCommit.class, events.get(1)).branch());
    assertEquals("v1", assertInstanceOf(SCMPublishTag.class, events.get(2)).tagName());
  }

  @Test
  void anAnnotatedTagCarriesItsTaggerAndPeelsToTheCommit() throws Exception {
    ObjectId head = commit("release me\n");
    ObjectId tag = annotatedTag("v2026.810.1", head, "the release\n");

    SCMPublishTag event =
        assertInstanceOf(SCMPublishTag.class, only(map(create("refs/tags/v2026.810.1", tag), false)));

    assertTrue(event.annotated());
    assertEquals(tag.name(), event.sha(), "the ref holds the TAG object");
    assertEquals(head.name(), event.targetSha(), "and it peels to the commit");
    assertEquals("Ada", event.taggerName());
    assertEquals("ada@local", event.taggerEmail());
    assertEquals("the release\n", event.message());
  }

  @Test
  void aLightweightTagIsItsOwnTargetAndHasNoTagger() throws Exception {
    ObjectId head = commit("tag me\n");

    SCMPublishTag event =
        assertInstanceOf(SCMPublishTag.class, only(map(create("refs/tags/nightly", head), false)));

    assertFalse(event.annotated());
    assertEquals(head.name(), event.sha());
    assertEquals(head.name(), event.targetSha());
    assertNull(event.taggerName());
    assertNull(event.taggerEmail());
    assertNull(event.message());
  }

  @Test
  void deletingABranchAndATagEachGetTheirOwnEventCarryingTheOldTip() throws Exception {
    ObjectId head = commit("gone\n");
    ObjectId tag = annotatedTag("v1", head, "gone too");

    List<QitsEvent> events =
        map(List.of(delete("refs/heads/feature/x", head), delete("refs/tags/v1", tag)), false);

    SCMDeleteBranch branch = assertInstanceOf(SCMDeleteBranch.class, events.get(0));
    assertEquals("feature/x", branch.branch());
    assertEquals(head.name(), branch.sha());

    SCMDeleteTag deletedTag = assertInstanceOf(SCMDeleteTag.class, events.get(1));
    assertEquals("v1", deletedTag.tagName());
    assertEquals(tag.name(), deletedTag.sha(), "a deleted annotated tag names the tag OBJECT");
  }

  @Test
  void theNoCiOptionLandsOnEveryCommitEventOfThePush() throws Exception {
    ObjectId main = commit("imported\n");
    ObjectId other = commit("imported too\n");

    List<QitsEvent> events =
        map(
            List.of(create("refs/heads/main", main), create("refs/heads/legacy", other)),
            true);

    assertEquals(2, events.size());
    for (QitsEvent event : events) {
      assertTrue(assertInstanceOf(SCMPublishCommit.class, event).suppressCi());
    }
  }

  @Test
  void aRefusedRefProducesNoEvent() throws Exception {
    ObjectId head = commit("refused\n");
    ReceiveCommand refused = create("refs/heads/main", head);
    // What ProtectedRefHook does to a push at the protected branch. The ref did not move, so
    // announcing it would have every consumer act on a push this host rejected.
    refused.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "protected ref");

    assertEquals(
        List.of(),
        PostReceiveEvents.of("r", "qits", "n", repo, List.of(refused), false, RECEIVED));
  }

  @Test
  void everyEventEchoesThePushAddressAndAnIdAddressedPushHasNone() throws Exception {
    ObjectId head = commit("addressed\n");
    ObjectId tag = annotatedTag("v1", head, "released");
    ObjectId gone = commit("gone\n");

    List<QitsEvent> addressed =
        map(
            List.of(
                create("refs/heads/main", head),
                create("refs/tags/v1", tag),
                delete("refs/heads/old", gone),
                delete("refs/tags/v0", gone)),
            false);

    assertEquals(PROJECT, assertInstanceOf(SCMPublishCommit.class, addressed.get(0)).projectId());
    assertEquals(REPO_NAME, assertInstanceOf(SCMPublishCommit.class, addressed.get(0)).repoName());
    assertEquals(PROJECT, assertInstanceOf(SCMPublishTag.class, addressed.get(1)).projectId());
    assertEquals(REPO_NAME, assertInstanceOf(SCMPublishTag.class, addressed.get(1)).repoName());
    assertEquals(PROJECT, assertInstanceOf(SCMDeleteBranch.class, addressed.get(2)).projectId());
    assertEquals(REPO_NAME, assertInstanceOf(SCMDeleteBranch.class, addressed.get(2)).repoName());
    assertEquals(PROJECT, assertInstanceOf(SCMDeleteTag.class, addressed.get(3)).projectId());
    assertEquals(REPO_NAME, assertInstanceOf(SCMDeleteTag.class, addressed.get(3)).repoName());

    // The internal scheme: qits-projects mirroring history the platform already announced. The
    // storage id is still there — it is what was written — and the two name fields are simply
    // absent rather than guessed at.
    ReceiveCommand mirrored = create("refs/heads/main", head);
    mirrored.setResult(ReceiveCommand.Result.OK);
    SCMPublishCommit event =
        assertInstanceOf(
            SCMPublishCommit.class,
            only(PostReceiveEvents.of("r", null, null, repo, List.of(mirrored), false, RECEIVED)));
    assertEquals("r", event.repoId());
    assertNull(event.projectId());
    assertNull(event.repoName());
  }

  @Test
  void aRefInNeitherNamespaceIsSilentRatherThanGuessedAt() throws Exception {
    ObjectId head = commit("noted\n");

    assertEquals(List.of(), map(create("refs/notes/commits", head), false));
    assertEquals(List.of(), map(create("refs/qits/whatever", head), false));
  }

  // --- helpers -------------------------------------------------------------------------------

  private List<QitsEvent> map(ReceiveCommand command, boolean suppressCi) {
    return map(List.of(command), suppressCi);
  }

  private List<QitsEvent> map(List<ReceiveCommand> commands, boolean suppressCi) {
    for (ReceiveCommand command : commands) {
      command.setResult(ReceiveCommand.Result.OK);
    }
    return PostReceiveEvents.of("r", PROJECT, REPO_NAME, repo, commands, suppressCi, RECEIVED);
  }

  private static QitsEvent only(List<QitsEvent> events) {
    assertEquals(1, events.size(), () -> "expected one event, got " + events);
    return events.get(0);
  }

  private static ReceiveCommand create(String ref, ObjectId to) {
    return new ReceiveCommand(ObjectId.zeroId(), to, ref);
  }

  private static ReceiveCommand update(String ref, ObjectId from, ObjectId to) {
    return new ReceiveCommand(from, to, ref);
  }

  private static ReceiveCommand delete(String ref, ObjectId from) {
    return new ReceiveCommand(from, ObjectId.zeroId(), ref);
  }

  private ObjectId commit(String message, ObjectId... parents) throws Exception {
    CommitBuilder builder = new CommitBuilder();
    builder.setTreeId(inserter.insert(new TreeFormatter()));
    builder.setParentIds(parents);
    builder.setAuthor(new PersonIdent("Ada", "ada@local", AUTHORED, ZoneOffset.UTC));
    builder.setCommitter(new PersonIdent("Rex", "rex@local", COMMITTED, ZoneOffset.UTC));
    builder.setMessage(message);
    ObjectId id = inserter.insert(builder);
    inserter.flush();
    return id;
  }

  private ObjectId annotatedTag(String name, ObjectId target, String message) throws Exception {
    TagBuilder builder = new TagBuilder();
    builder.setTag(name);
    builder.setObjectId(target, Constants.OBJ_COMMIT);
    builder.setTagger(new PersonIdent("Ada", "ada@local", COMMITTED, ZoneOffset.UTC));
    builder.setMessage(message);
    ObjectId id = inserter.insert(builder);
    inserter.flush();
    return id;
  }
}
