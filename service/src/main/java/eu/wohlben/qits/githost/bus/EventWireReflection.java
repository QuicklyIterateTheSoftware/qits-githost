package eu.wohlben.qits.githost.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus binds to JSON, told to native-image. No code, no bean, nothing at runtime: the
 * annotation is the entire content, and this class exists so that the annotation has somewhere to
 * live that can say why.
 *
 * <p><b>Why nothing registers these automatically.</b> Quarkus registers reflection for the classes
 * <em>it</em> knows are serialized — a REST resource's parameters and return types, whatever the CDI
 * {@code ObjectMapper} is handed. {@code CanonicalJson} builds its <b>own</b> {@code ObjectMapper}
 * by hand, deliberately and permanently: the canonical form is a wire contract qits-events compares
 * byte-for-byte, so it must not be downstream of any application's {@code ObjectMapperCustomizer}.
 * Correct, and this is the price — to the build step scanning for what needs reflecting on, that
 * mapper and everything it touches are invisible.
 *
 * <p><b>It cost qits-ci a silent outage, which is why this file is copied rather than reasoned
 * about.</b> On its deployed binary every publish died inside {@code CanonicalJson}'s writer with
 * Jackson's {@code No serializer found … you may need to configure reflection}: a record with no
 * reflection metadata has no components to find. The throw happens while the envelope is being
 * built, so the event never reached the outbox either — not a delayed delivery, a lost one. The JVM
 * suite was green throughout and structurally had to be.
 *
 * <p><b>Why these types.</b> The four {@code SCM*} records are the whole of this service's
 * vocabulary and every one of them is serialized on a push; {@link EventEnvelope} is the {@code PUT}
 * body. {@code EventFrame} is deliberately absent — it is what arrives on {@code /events/stream},
 * and this service publishes only: it registers no listener, so the subscriber never dials and no
 * frame is ever read. The day a listener lands here, its event class and {@code EventFrame} join
 * this list, and {@link eu.wohlben.qits.githost.bus} is where they go.
 *
 * <p><b>An event this service only publishes is exactly as dependent on this list</b>, which is
 * worth stating because "nothing binds it back" reads like a reason to skip it. The failure is on
 * the writing side.
 *
 * <p><b>And the mix-in by name — that one is measured, not reasoned.</b> {@code
 * CanonicalJson$QitsEventMixin} is the private nested class that keeps {@code QitsEvent}'s four
 * declared methods — {@code eventId} above all — out of a payload, and Jackson finds its {@code
 * @JsonIgnore}s by calling {@code getDeclaredMethods()} on it, which is reflection like any other.
 * Without it a binary publishes a payload carrying {@code eventId}: no crash, no log, and a wire
 * contract violation that qits-events answers with a 400 the next time the id is replayed. It is
 * named as a string because it is private and stays private; {@link EventWireReflectionTest}
 * resolves the string so it cannot rot unnoticed.
 *
 * <p>All of this is in {@code service/} because {@code service/} is the deployable, and the
 * deployable is what tells the builder about itself. {@code githost-events} is a vocabulary jar a
 * consumer depends on — its own registration is that consumer's business, on the same argument.
 */
@RegisterForReflection(
    targets = {
      SCMPublishCommit.class,
      SCMPublishTag.class,
      SCMDeleteBranch.class,
      SCMDeleteTag.class,
      EventEnvelope.class
    },
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
