package eu.wohlben.qits.githost.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The git host's four events, on the wire. Plain JUnit — an event class is data, and the serializer
 * it is asserted against builds its own mapper precisely so no container is needed to know what it
 * emits.
 *
 * <p>These assertions are the contract every consumer is written against, so a change here that is
 * not also a change there is a cross-repo break rather than a refactor.
 */
class ScmEventsTest {

  private static final Instant AUTHORED = Instant.parse("2026-08-10T09:00:00Z");
  private static final Instant COMMITTED = Instant.parse("2026-08-10T09:01:00Z");
  private static final Instant RECEIVED = Instant.parse("2026-08-10T09:02:03Z");

  private static SCMPublishCommit aCommit() {
    return new SCMPublishCommit(
        "qits-githost",
        "qits",
        "qits-githost-repo",
        "main",
        "1111111111111111111111111111111111111111",
        "2222222222222222222222222222222222222222",
        List.of("1111111111111111111111111111111111111111"),
        "qits",
        "qits@local",
        AUTHORED,
        COMMITTED,
        "Serve one file without cloning",
        false,
        RECEIVED);
  }

  private static SCMPublishTag anAnnotatedTag() {
    return new SCMPublishTag(
        "qits-githost",
        "qits",
        "qits-githost-repo",
        "v2026.810.1",
        "3333333333333333333333333333333333333333",
        "2222222222222222222222222222222222222222",
        "qits",
        "qits@local",
        "release",
        true,
        RECEIVED);
  }

  @Test
  void everySignatureIsTheClassNameAndTheNameFollowsIt() {
    for (QitsEvent event :
        List.of(
            aCommit(),
            anAnnotatedTag(),
            new SCMDeleteBranch("r", "p", "n", "feature/x", "44", RECEIVED),
            new SCMDeleteTag("r", "p", "n", "v1", "55", RECEIVED))) {
      assertEquals(event.getClass().getSimpleName(), event.signature());
      assertEquals(event.signature(), event.name());
    }
  }

  @Test
  void occurredAtIsWhenTheHostTookThePushRatherThanWhenTheWorkWasWritten() {
    // The two clocks a push carries are the pusher's; the log is ordered by the platform's.
    assertEquals(RECEIVED, aCommit().occurredAt());
    assertNotEquals(AUTHORED, aCommit().occurredAt());
    assertNotEquals(COMMITTED, aCommit().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    SCMPublishCommit event = aCommit();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two pushes of the same facts are two occurrences and must not collide on one id.
    assertNotEquals(first, aCommit().eventId());
  }

  @Test
  void theCommitEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(aCommit());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("SCMPublishCommit", json.get("name").asText());
    assertEquals("2026-08-10T09:02:03Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"authorEmail\":\"qits@local\",\"authorName\":\"qits\","
            + "\"authoredAt\":\"2026-08-10T09:00:00Z\",\"branch\":\"main\","
            + "\"committedAt\":\"2026-08-10T09:01:00Z\","
            + "\"message\":\"Serve one file without cloning\","
            + "\"oldSha\":\"1111111111111111111111111111111111111111\","
            + "\"parents\":[\"1111111111111111111111111111111111111111\"],"
            + "\"projectId\":\"qits\","
            + "\"receivedAt\":\"2026-08-10T09:02:03Z\",\"repoId\":\"qits-githost\","
            + "\"repoName\":\"qits-githost-repo\","
            + "\"sha\":\"2222222222222222222222222222222222222222\",\"suppressCi\":false}",
        json.get("payload").asText());
  }

  @Test
  void thePushAddressRidesEveryEventAndIsAbsentWhenTheIdSchemeWasUsed() {
    // The trick that keeps this host domain-free: the public clone url is /git/<projectId>/<repoName>
    // and the route echoes both onto the event, resolving nothing. A push on the internal
    // /git/<storageId> scheme legitimately has neither — qits-projects mirroring history the
    // platform already announced — and the payload then simply omits the two keys, which is the
    // shape an older consumer already reads.
    assertEquals("qits", aCommit().projectId());
    assertEquals("qits-githost-repo", aCommit().repoName());
    assertEquals("qits", anAnnotatedTag().projectId());

    for (QitsEvent addressed :
        List.of(
            aCommit(),
            anAnnotatedTag(),
            new SCMDeleteBranch("r", "qits", "n", "feature/x", "44", RECEIVED),
            new SCMDeleteTag("r", "qits", "n", "v1", "55", RECEIVED))) {
      String payload = CanonicalJson.payload(addressed);
      assertTrue(payload.contains("\"projectId\":\"qits\""), payload);
      assertTrue(payload.contains("\"repoName\":\"n\"") || payload.contains("\"repoName\":\"qits-githost-repo\""), payload);
    }

    for (QitsEvent unaddressed :
        List.of(
            new SCMPublishCommit(
                "r", null, null, "main", "old", "new", List.of("old"), "q", "q@l", AUTHORED,
                COMMITTED, "mirror", false, RECEIVED),
            new SCMPublishTag(
                "r", null, null, "v1", "abc", "abc", null, null, null, false, RECEIVED),
            new SCMDeleteBranch("r", null, null, "feature/x", "44", RECEIVED),
            new SCMDeleteTag("r", null, null, "v1", "55", RECEIVED))) {
      String payload = CanonicalJson.payload(unaddressed);
      assertFalse(payload.contains("projectId"), payload);
      assertFalse(payload.contains("repoName"), payload);
    }
  }

  @Test
  void aConsumerReadsThePushAddressBackOutOfThePayload() {
    SCMPublishCommit commit =
        CanonicalJson.payloadTo(CanonicalJson.payload(aCommit()), SCMPublishCommit.class);
    assertEquals("qits", commit.projectId());
    assertEquals("qits-githost-repo", commit.repoName());

    // The older shape, which carried neither key: it must still bind, with both fields null. That is
    // what makes this addition additive for a consumer replaying its history.
    SCMDeleteBranch old =
        CanonicalJson.payloadTo(
            "{\"branch\":\"feature/x\",\"receivedAt\":\"2026-08-10T09:02:03Z\",\"repoId\":\"r\","
                + "\"sha\":\"44\"}",
            SCMDeleteBranch.class);
    assertEquals("feature/x", old.branch());
    assertNull(old.projectId());
    assertNull(old.repoName());
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    SCMPublishCommit event = aCommit();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("\"occurredAt\""), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aRootCommitSaysItHasNoParentsRatherThanOmittingTheKey() {
    SCMPublishCommit root =
        new SCMPublishCommit(
            "r", "qits", "n", "main", "0".repeat(40), "abc", null, "q", "q@l", AUTHORED,
            COMMITTED, "seed", false, RECEIVED);

    assertEquals(List.of(), root.parents());
    assertTrue(CanonicalJson.payload(root).contains("\"parents\":[]"));
  }

  @Test
  void aLightweightTagOmitsTheTaggerRatherThanNullingIt() {
    SCMPublishTag lightweight =
        new SCMPublishTag(
            "r", "qits", "n", "v1", "abc", "abc", null, null, null, false, RECEIVED);

    String payload = CanonicalJson.payload(lightweight);

    assertFalse(payload.contains("taggerName"), payload);
    assertFalse(payload.contains("taggerEmail"), payload);
    assertFalse(payload.contains("message"), payload);
    assertFalse(payload.contains("null"), payload);
    assertTrue(payload.contains("\"annotated\":false"), payload);
  }

  @Test
  void aSubscriberReadsEveryPayloadBackIntoItsEvent() {
    SCMPublishCommit commit =
        CanonicalJson.payloadTo(CanonicalJson.payload(aCommit()), SCMPublishCommit.class);
    assertEquals("main", commit.branch());
    assertEquals(List.of("1111111111111111111111111111111111111111"), commit.parents());
    assertEquals(AUTHORED, commit.authoredAt());
    assertEquals(COMMITTED, commit.committedAt());
    assertEquals(RECEIVED, commit.occurredAt());

    SCMPublishTag tag =
        CanonicalJson.payloadTo(CanonicalJson.payload(anAnnotatedTag()), SCMPublishTag.class);
    assertEquals("v2026.810.1", tag.tagName());
    assertEquals("2222222222222222222222222222222222222222", tag.targetSha());
    assertTrue(tag.annotated());

    SCMDeleteBranch deletedBranch =
        CanonicalJson.payloadTo(
            CanonicalJson.payload(
                new SCMDeleteBranch("r", "qits", "n", "feature/x", "44", RECEIVED)),
            SCMDeleteBranch.class);
    assertEquals("feature/x", deletedBranch.branch());
    assertEquals("44", deletedBranch.sha());

    SCMDeleteTag deletedTag =
        CanonicalJson.payloadTo(
            CanonicalJson.payload(new SCMDeleteTag("r", "qits", "n", "v1", "55", RECEIVED)),
            SCMDeleteTag.class);
    assertEquals("v1", deletedTag.tagName());
    assertEquals("55", deletedTag.sha());
  }

  @Test
  void theNoCiPushOptionIsAFactOnTheEventRatherThanASuppressedEvent() {
    // The old notifier decided for its consumers: -o qits.no-ci skipped the CI POST and sent the
    // projects one. The option is data now, so a third consumer can have its own opinion.
    SCMPublishCommit suppressed =
        new SCMPublishCommit(
            "r", "qits", "n", "main", "old", "new", List.of("old"), "q", "q@l", AUTHORED,
            COMMITTED, "import", true, RECEIVED);

    assertTrue(suppressed.suppressCi());
    assertTrue(CanonicalJson.payload(suppressed).contains("\"suppressCi\":true"));
  }
}
