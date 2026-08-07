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
 * The post-receive fan-out with <b>no client credentials configured</b> — the shipped default. Both
 * consumers get the event and the ci one carries no bearer, because there is no idp to have minted
 * one.
 *
 * <p>{@link CiPostReceiveBearerTest} is the same delivery with the credentials in place; {@link
 * GitHostNoCiOptionTest} drives the same fan-out through a real push.
 */
@QuarkusTest
@TestProfile(PostReceiveNotifierTest.IntakesStubbed.class)
class PostReceiveNotifierTest {

  public static class IntakesStubbed implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Only the addresses move: quarkus.oidc-client.client-enabled stays at its shipped false.
      return Map.of(
          "qits.ci.intake-url", StubIntake.ciIntakeUrl(),
          "qits.projects.intake-url", StubIntake.projectsIntakeUrl());
    }
  }

  @Inject PostReceiveNotifier notifier;

  @BeforeEach
  void forgetPreviousDeliveries() {
    StubIntake.reset();
  }

  @Test
  void bothConsumersGetTheEventAndCiGetsNoBearer() throws Exception {
    notifier.onPostReceive("a-repo", List.of(pushOf("refs/heads/main")), false);

    assertTrue(StubIntake.awaitCiDelivery(), "the event should reach the ci intake");
    assertTrue(
        StubIntake.awaitProjectsDelivery(), "and the projects intake, which backs the repo up");
    assertNull(
        StubIntake.lastAuthorization(),
        "with no client credentials there is no token to attach, and the POST goes out bare");
  }

  @Test
  void aSuppressedCiEventStillReachesProjects() throws Exception {
    notifier.onPostReceive("a-repo", List.of(pushOf("refs/heads/main")), true);

    assertTrue(
        StubIntake.awaitProjectsDelivery(),
        "the backup is owed for every push, including one CI was told to ignore");
    assertEquals(0, StubIntake.ciDeliveries(), "-o qits.no-ci suppresses the ci delivery");
  }

  @Test
  void onlyBranchUpdatesAreEvents() throws Exception {
    ReceiveCommand tag = pushOf("refs/tags/v1");
    ReceiveCommand rejected = pushOf("refs/heads/nope");
    rejected.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON);

    notifier.onPostReceive("a-repo", List.of(tag, rejected), false);

    StubIntake.awaitSpuriousDelivery();
    assertEquals(0, StubIntake.ciDeliveries(), "neither a tag nor a rejected push is an event");
    assertEquals(
        0,
        StubIntake.projectsDeliveries(),
        "and the projects side filters the same way — tags are its own backup sweep's job");
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
}
