package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The post-receive delivery with <b>no client credentials configured</b> — the shipped default, and
 * the half of this change that must look like no change at all. The event still leaves, and it
 * carries no bearer, because there is no idp to have minted one.
 *
 * <p>{@link CiPostReceiveBearerTest} is the same delivery with the credentials in place.
 */
@QuarkusTest
@TestProfile(CiPostReceiveNotifierTest.IntakeStubbed.class)
class CiPostReceiveNotifierTest {

  public static class IntakeStubbed implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Only the address moves: quarkus.oidc-client.client-enabled stays at its shipped false.
      return Map.of("qits.ci.intake-url", StubCiIntake.intakeUrl());
    }
  }

  @Inject CiPostReceiveNotifier notifier;

  @BeforeEach
  void forgetPreviousDeliveries() {
    StubCiIntake.reset();
  }

  @Test
  void theEventIsDeliveredWithoutABearer() throws Exception {
    notifier.onPostReceive("a-repo", List.of(pushOf("refs/heads/main")));

    assertTrue(awaitDelivery(), "the event should reach the intake");
    assertNull(
        StubCiIntake.lastAuthorization(),
        "with no client credentials there is no token to attach, and the POST goes out bare");
  }

  @Test
  void onlyBranchUpdatesAreEvents() throws Exception {
    ReceiveCommand tag = pushOf("refs/tags/v1");
    ReceiveCommand rejected = pushOf("refs/heads/nope");
    rejected.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON);

    notifier.onPostReceive("a-repo", List.of(tag, rejected));

    Thread.sleep(300);
    assertEquals(0, StubCiIntake.deliveries(), "neither a tag nor a rejected push is an event");
  }

  static ReceiveCommand pushOf(String ref) {
    ReceiveCommand command =
        new ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("1111111111111111111111111111111111111111"),
            ref);
    command.setResult(ReceiveCommand.Result.OK);
    return command;
  }

  /** The notifier is fire-and-forget, so the assertion has to wait for the POST rather than it. */
  static boolean awaitDelivery() throws InterruptedException {
    for (int attempt = 0; attempt < 50; attempt++) {
      if (StubCiIntake.deliveries() > 0) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }
}
