package eu.wohlben.qits.githost.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection} — which is to say, guards the <em>completeness</em> of the
 * registration, because its correctness is not something this suite can reach.
 *
 * <p><b>Say plainly what a JVM test can and cannot prove here.</b> On a JVM every class reflects
 * whether anyone registered it or not, so nothing below would fail if the annotation were deleted
 * tomorrow, except the assertions that read the annotation itself. Only the <b>native artifact</b>,
 * running, proves that the registration does its job. What is written here is the part that IS
 * checkable: that the registered set still covers every type this service's wire path touches, and
 * that the one entry named as a string still resolves. A test pretending to more than that —
 * "native reflection works" asserted in surefire — would pass vacuously and be worse than none.
 *
 * <p>Plain JUnit: nothing here needs a container, and this service registers no listener bean to
 * cross-check the set against (qits-ci's version does, which is the one assertion of its that has
 * no counterpart here).
 */
class EventWireReflectionTest {

  /** The private nested mix-in {@link EventWireReflection} can only name as a string. */
  private static final String MIXIN =
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  private static final Instant WHEN = Instant.parse("2026-08-10T09:02:03Z");

  private static RegisterForReflection registration() {
    return EventWireReflection.class.getAnnotation(RegisterForReflection.class);
  }

  @Test
  void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    assertNotNull(registration(), "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            SCMPublishCommit.class,
            SCMPublishTag.class,
            SCMDeleteBranch.class,
            SCMDeleteTag.class,
            EventEnvelope.class),
        Set.of(registration().targets()),
        "the four events out and the PUT body — a fifth event means adding it here");
  }

  /**
   * The rule that generalises: this service's vocabulary is the {@code githost-events} module, and
   * an event class that is published but not registered is a binary whose push announces nothing.
   * Asserted against the events {@link PostReceiveEvents} can actually produce, so a fifth record
   * that the mapper starts emitting has to appear above.
   */
  @Test
  void everyEventThisServicePublishesIsRegistered() {
    Set<Class<?>> registered = Set.of(registration().targets());
    for (Class<? extends QitsEvent> published :
        List.of(
            SCMPublishCommit.class,
            SCMPublishTag.class,
            SCMDeleteBranch.class,
            SCMDeleteTag.class)) {
      assertTrue(
          registered.contains(published),
          published.getName() + " is published on a push but is not registered for reflection");
    }
  }

  /**
   * The string entry, kept honest. A rename or a move of the mix-in would otherwise leave a
   * registration that names nothing, silently — and its consequence is not a crash but {@code
   * eventId} appearing in the canonical payload, which is a wire contract violation qits-events
   * answers with a 400 the next time the same id is replayed.
   */
  @Test
  void theMixinNamedByStringStillExistsAndStillHidesTheIdentity() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertEquals(MIXIN, Set.of(registration().classNames()).iterator().next());
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(
        eventId.getAnnotation(JsonIgnore.class),
        "the mix-in is registered because this @JsonIgnore is read by reflection");
  }

  /**
   * The registered types in one flow, which is the flow a binary would die in: an event is
   * canonicalized into an envelope and the envelope is written as the PUT body. Every step is a
   * Jackson bind of a type nothing else in this application hands to the CDI {@code ObjectMapper}.
   */
  @Test
  void theWholeWirePathBindsOnTheWayOut() {
    SCMPublishCommit out =
        new SCMPublishCommit(
            "r", "qits", "testing-repo", "main", "0".repeat(40), "abc", List.of(), "Ada",
            "ada@local", WHEN, WHEN, "seed", false, WHEN);

    JsonNode body = CanonicalJson.parse(CanonicalJson.envelope(EventEnvelope.of(out)));

    assertEquals("SCMPublishCommit", body.get("name").asText());
    assertTrue(body.get("payload").isTextual(), "payload is a string the server stores verbatim");
    assertTrue(
        body.get("payload").asText().contains("\"branch\":\"main\""),
        "the record's components are what the mapper finds by reflection");
  }
}
