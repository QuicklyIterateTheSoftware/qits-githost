package eu.wohlben.qits.githost;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seed capability is not an alternate system identity or a standing Git credential. */
class BootstrapIngressCredentialTest {

  private static final String CAPABILITY = "a-high-entropy-bootstrap-capability-not-a-system-token";

  private BootstrapIngressCredential credential(Instant expiry) {
    return new BootstrapIngressCredential(
        new BootstrapIngressCredential.Config(
            true,
            BootstrapIngressCredential.sha256(CAPABILITY),
            "qits-bootstrap",
            "refs/heads/bootstrap/*",
            expiry));
  }

  @Test
  void permitsOnlyTheExactRepositoryUntilItsRunExpires() {
    assertTrue(credential(Instant.parse("2030-01-01T00:00:00Z"))
        .permits(CAPABILITY, "qits-bootstrap", Instant.parse("2029-01-01T00:00:00Z")));
    assertFalse(credential(Instant.parse("2030-01-01T00:00:00Z"))
        .permits(CAPABILITY, "qits-ci", Instant.parse("2029-01-01T00:00:00Z")));
    assertFalse(credential(Instant.parse("2020-01-01T00:00:00Z"))
        .permits(CAPABILITY, "qits-bootstrap", Instant.parse("2029-01-01T00:00:00Z")));
    assertFalse(credential(Instant.parse("2030-01-01T00:00:00Z"))
        .permits("forged", "qits-bootstrap", Instant.parse("2029-01-01T00:00:00Z")));
  }
}
