package eu.wohlben.qits.githost;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.oidc.client.OidcClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Delivers the git host's post-receive events to ci's HTTP intake (docs/epics/qits-ci/): one {@code
 * {repoId, branch, oldSha, newSha}} POST per successfully updated <b>branch</b> ref (deletions and
 * non-branch refs are ignored). The event stays an HTTP call even while ci lives in-process —
 * that's the wire contract an extracted ci service receives unchanged; only {@code
 * qits.ci.intake-url} moves.
 *
 * <p>Fire-and-forget, the {@code OtelForwarder} idiom: the hook fires inside {@code
 * ReceivePack.receive(...)} — before the push response is written — so this must never block or
 * throw; failures are swallowed at debug (a missed event just means no advisory run for that push).
 *
 * <p><b>The credential.</b> qits-ci's intake wants a bearer minted by qits-idp for {@code
 * aud=qits-ci}; quarkus-oidc-client fetches and caches it and this class only attaches it. Both the
 * bearer and the older {@code X-CI-Token} are optional and independent, so a deployment can be on
 * either, both or neither — which is what lets the two services cut over one at a time. With no
 * client credentials configured nothing is fetched and the POST goes out exactly as it always has.
 */
@ApplicationScoped
public class CiPostReceiveNotifier {

  private static final Logger LOG = Logger.getLogger(CiPostReceiveNotifier.class);

  /**
   * An <b>instance</b> field, not a static one, and that is a native-image constraint rather than a
   * style preference: a static {@code HttpClient} is built while the class initialiser runs, which
   * under GraalVM is image-build time, and native-image then refuses the image ("An object of type
   * 'jdk.internal.net.http.HttpClientFacade' was found in the image heap"). Even if it were allowed
   * it would be wrong — an {@code HttpClient} owns a selector thread and an executor, neither of
   * which survives being frozen into a binary. This bean is {@code @ApplicationScoped}, so there is
   * still exactly one client per process; it is now created when the process starts rather than
   * when the image is compiled.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.ci.intake-url")
  String intakeUrl;

  // The same static secret the intake's CiTokenFilter checks — blank (the dev/test default) sends
  // no header, matching the filter's open mode.
  @ConfigProperty(name = "qits.ci.token")
  Optional<String> token;

  /**
   * Whether a machine token is attached. It is the oidc-client's own switch rather than a key of our
   * own, so there is one thing to set and nothing to keep in step: with the client disabled there
   * are no credentials to fetch a token with, and asking it anyway throws. Off is the shipped
   * default and means "post as this service always has".
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled", defaultValue = "false")
  boolean machineTokenEnabled;

  /** Fetches, caches and refreshes the bearer for qits-ci; see the oidc-client block in config. */
  @Inject OidcClient oidcClient;

  @Inject ObjectMapper objectMapper;

  /** The {@code PostReceiveHook} body — one event per updated branch ref of the push. */
  public void onPostReceive(String repoId, Collection<ReceiveCommand> commands) {
    for (ReceiveCommand command : commands) {
      try {
        if (command.getResult() != ReceiveCommand.Result.OK
            || command.getType() == ReceiveCommand.Type.DELETE
            || !command.getRefName().startsWith(Constants.R_HEADS)) {
          continue;
        }
        post(
            repoId,
            command.getRefName().substring(Constants.R_HEADS.length()),
            command.getOldId().name(),
            command.getNewId().name());
      } catch (Exception e) {
        LOG.debugf("CI post-receive event for %s skipped: %s", repoId, e.toString());
      }
    }
  }

  private void post(String repoId, String branch, String oldSha, String newSha) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of("repoId", repoId, "branch", branch, "oldSha", oldSha, "newSha", newSha));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(intakeUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    token
        .map(String::trim)
        .filter(t -> !t.isEmpty())
        .ifPresent(t -> request.header("X-CI-Token", t));
    // Non-blocking like everything else on this path: the token fetch is a Uni, and the send hangs
    // off its completion rather than waiting for it.
    bearer()
        .subscribe()
        .with(
            bearer -> {
              bearer.ifPresent(b -> request.header("Authorization", "Bearer " + b));
              send(request.build(), repoId, branch);
            },
            failure -> {
              // A token we could not get is not a reason to drop the event. qits-ci with its gate
              // off takes it either way, and with the gate on it refuses it — which is the right
              // answer for a caller that cannot prove who it is.
              LOG.debugf("CI token for %s@%s unavailable: %s", repoId, branch, failure);
              send(request.build(), repoId, branch);
            });
  }

  /** The bearer for qits-ci, or empty when this deployment has no client credentials. */
  private Uni<Optional<String>> bearer() {
    if (!machineTokenEnabled) {
      return Uni.createFrom().item(Optional.empty());
    }
    return oidcClient.getTokens().map(tokens -> Optional.of(tokens.getAccessToken()));
  }

  private void send(HttpRequest request, String repoId, String branch) {
    client
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, failure) -> {
              if (failure != null) {
                LOG.debugf("CI event for %s@%s failed: %s", repoId, branch, failure);
              } else if (response.statusCode() >= 400) {
                LOG.debugf(
                    "CI event for %s@%s rejected: %d", repoId, branch, response.statusCode());
              }
            });
  }
}
