package eu.wohlben.qits.githost;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * The seed bootstrap's one-run Git capability.  This is intentionally not an OIDC identity: it
 * has no roles, API access or standing client secret, and the seed compose file supplies only its
 * SHA-256 fingerprint.  It disappears when the seed container is replaced.
 */
final class BootstrapIngressCredential {
  static final String HEADER = "X-Qits-Bootstrap-Git-Capability";

  record Config(boolean enabled, String secretHash, String repository, String refPattern,
                Instant expiresAt) {}

  private final Config config;

  BootstrapIngressCredential(Config config) {
    this.config = config;
  }

  boolean permits(String capability, String repository, Instant now) {
    if (!config.enabled() || now.isAfter(config.expiresAt()) || capability == null
        || repository == null || !repository.equals(config.repository())) {
      return false;
    }
    String supplied = sha256(capability);
    return MessageDigest.isEqual(config.secretHash().getBytes(StandardCharsets.US_ASCII),
        supplied.getBytes(StandardCharsets.US_ASCII));
  }

  String refPattern() {
    return config.refPattern();
  }

  static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
