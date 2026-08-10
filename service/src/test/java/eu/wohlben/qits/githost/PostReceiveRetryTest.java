package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The retry, played against an intake that is down for a moment. This is the bootstrap failure that
 * was measured twice on the live platform: the database container is redeployed one phase before the
 * next push, qits-ci's connection pool is severed, its intake answers 500, and with one attempt and
 * nothing else the event was gone and no CI run ever started.
 *
 * <p>The delays are milliseconds here, set by profile. That is what {@code
 * qits.post-receive.retry-delays} is configurable for — the shipped schedule spans three minutes,
 * and a suite that waited it out would be a suite nobody runs.
 */
@QuarkusTest
@TestProfile(PostReceiveRetryTest.FastRetries.class)
class PostReceiveRetryTest {

  public static class FastRetries implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.ci.intake-url", StubIntake.ciIntakeUrl(),
          "qits.projects.intake-url", StubIntake.projectsIntakeUrl(),
          // Four retries, the shipped count, so the give-up case below proves the real bound.
          "qits.post-receive.retry-delays", "PT0.05S,PT0.1S,PT0.15S,PT0.2S");
    }
  }

  @Inject PostReceiveNotifier notifier;

  @BeforeEach
  void forgetPreviousDeliveries() {
    StubIntake.reset();
  }

  @Test
  void anIntakeThatRefusesTwiceStillGetsTheEventExactlyOnce() throws Exception {
    StubIntake.ciRefusesNext(2);

    notifier.onPostReceive("a-repo", List.of(pushOf()), false);

    assertTrue(StubIntake.awaitCiDelivery(), "the third attempt should land the event");
    StubIntake.awaitSpuriousDelivery();
    assertEquals(3, StubIntake.ciAttempts(), "two refusals and the attempt that succeeded");
    assertEquals(1, StubIntake.ciDeliveries(), "an event survives an outage; it does not double");
  }

  @Test
  void theBackupTriggerIsRetriedToo() throws Exception {
    StubIntake.projectsRefusesNext(1);

    notifier.onPostReceive("a-repo", List.of(pushOf()), false);

    assertTrue(StubIntake.awaitProjectsDelivery(), "both consumers retry, not just ci");
    StubIntake.awaitSpuriousDelivery();
    assertEquals(2, StubIntake.projectsAttempts(), "one refusal and the attempt that succeeded");
    assertEquals(1, StubIntake.projectsDeliveries(), "and the backup fires once");
  }

  @Test
  void aDeliveryThatSucceedsFirstTryIsPostedOnce() throws Exception {
    notifier.onPostReceive("a-repo", List.of(pushOf()), false);

    assertTrue(StubIntake.awaitCiDelivery(), "nothing about the retry changes the happy path");
    assertTrue(StubIntake.awaitProjectsDelivery());
    StubIntake.awaitSpuriousDelivery();
    assertEquals(1, StubIntake.ciAttempts(), "one POST per consumer, as before");
    assertEquals(1, StubIntake.projectsAttempts());
  }

  @Test
  void theRetryIsBoundedAndTheGiveUpIsLogged() throws Exception {
    StubIntake.ciRefusesNext(99);

    notifier.onPostReceive("a-repo", List.of(pushOf()), false);

    assertTrue(StubIntake.awaitCiAttempts(5), "four delays mean five attempts");
    StubIntake.awaitSpuriousDelivery();
    assertEquals(5, StubIntake.ciAttempts(), "and then it stops, rather than retrying forever");
    assertEquals(0, StubIntake.ciDeliveries(), "the give-up is a WARN in the log, nothing else");
  }

  private static ReceiveCommand pushOf() {
    return PostReceiveNotifierTest.pushOf("refs/heads/main");
  }
}
