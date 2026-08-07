package eu.wohlben.qits.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ci half of the delivery <b>with</b> client credentials: the notifier fetches a token from the
 * idp's token endpoint and presents it to qits-ci as a bearer.
 *
 * <p>The stub idp hands back an opaque string, which is faithful — quarkus-oidc-client never
 * inspects an access token, it only caches it and passes it on. What the assertion is really about
 * is that the fetch happens at all and that its result reaches the wire.
 *
 * <p>Only the ci url is stubbed here; the projects event goes to the suite's closed port, which is
 * the honest posture for a consumer this test says nothing about.
 */
@QuarkusTest
@TestProfile(CiPostReceiveBearerTest.WithClientCredentials.class)
class CiPostReceiveBearerTest {

  public static class WithClientCredentials implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.ci.intake-url", StubIntake.ciIntakeUrl(),
          "quarkus.oidc-client.client-enabled", "true",
          "quarkus.oidc-client.auth-server-url", StubIntake.idpUrl(),
          "quarkus.oidc-client.credentials.secret", "a-test-client-secret");
    }
  }

  @Inject PostReceiveNotifier notifier;

  @BeforeEach
  void forgetPreviousDeliveries() {
    StubIntake.reset();
  }

  @Test
  void theEventCarriesTheBearerTheIdpIssued() throws Exception {
    notifier.onPostReceive(
        "a-repo", List.of(PostReceiveNotifierTest.pushOf("refs/heads/main")), false);

    assertTrue(StubIntake.awaitCiDelivery(), "the event should reach the intake");
    assertEquals(
        "Bearer " + StubIntake.ACCESS_TOKEN,
        StubIntake.lastAuthorization(),
        "the token from the idp travels as the intake's credential");
  }
}
