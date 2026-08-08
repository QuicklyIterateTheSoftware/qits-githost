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
 * Delivers the git host's post-receive events to the two services that act on a push: ci's HTTP
 * intake (docs/epics/qits-ci/), which starts a run, and projects' identical intake, which pushes the
 * repository to its GitHub sync target. Each gets one {@code {repoId, branch, oldSha, newSha}} POST
 * per successfully updated <b>branch</b> ref (deletions and non-branch refs are ignored; the tag
 * side of a backup is projects' own sweep). The event stays an HTTP call even while a consumer lives
 * in-process — that's the wire contract an extracted service receives unchanged; only {@code
 * qits.ci.intake-url} and {@code qits.projects.intake-url} move.
 *
 * <p>One class rather than one per consumer, because the two deliveries share everything that is
 * hard here: the ref filter, the event body, the fire-and-forget send, and the single outbound
 * {@link HttpClient} the native image constrains (see the field below). What differs is the
 * credential, and only ci has one.
 *
 * <p><b>{@code -o qits.no-ci} suppresses the ci delivery and nothing else.</b> The projects event
 * fires for every push, including one the pusher told ci to ignore: the option exists so an imported
 * history does not queue a run per branch, and a backup must happen for that push exactly as for any
 * other. {@link GitHostRoutes} reads the option and passes it in.
 *
 * <p>Fire-and-forget, the {@code OtelForwarder} idiom: the hook fires inside {@code
 * ReceivePack.receive(...)} — before the push response is written — so this must never block or
 * throw; failures are swallowed at debug (a missed event just means no advisory run, or a backup
 * that waits for the next push). The two consumers are independent: an unreachable one costs the
 * other nothing.
 *
 * <p><b>The credential.</b> qits-ci's intake wants a bearer minted by qits-platform-idp for {@code
 * aud=qits-ci}; quarkus-oidc-client fetches and caches it and this class only attaches it. Both the
 * bearer and the older {@code X-CI-Token} are optional and independent, so a deployment can be on
 * either, both or neither — which is what lets the two services cut over one at a time. With no
 * client credentials configured nothing is fetched and the POST goes out exactly as it always has.
 * The projects event carries neither: that intake is unguarded on qits-net today, and a token minted
 * for {@code aud=qits-ci} would be the wrong one to send it anyway.
 */
@ApplicationScoped
public class PostReceiveNotifier {

  private static final Logger LOG = Logger.getLogger(PostReceiveNotifier.class);

  /**
   * An <b>instance</b> field, not a static one, and that is a native-image constraint rather than a
   * style preference: a static {@code HttpClient} is built while the class initialiser runs, which
   * under GraalVM is image-build time, and native-image then refuses the image ("An object of type
   * 'jdk.internal.net.http.HttpClientFacade' was found in the image heap"). Even if it were allowed
   * it would be wrong — an {@code HttpClient} owns a selector thread and an executor, neither of
   * which survives being frozen into a binary. This bean is {@code @ApplicationScoped}, so there is
   * still exactly one client per process — one for both consumers; it is now created when the
   * process starts rather than when the image is compiled.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.ci.intake-url")
  String ciIntakeUrl;

  /** Where qits-projects takes the same event and answers it with a backup push. */
  @ConfigProperty(name = "qits.projects.intake-url")
  String projectsIntakeUrl;

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

  /**
   * The {@code PostReceiveHook} body — one event per updated branch ref of the push, fanned out to
   * both consumers.
   *
   * @param ciSuppressed whether the push carried {@code -o qits.no-ci}. It skips the ci delivery
   *     only; projects is told either way, because a backup is owed for every push.
   */
  public void onPostReceive(
      String repoId, Collection<ReceiveCommand> commands, boolean ciSuppressed) {
    for (ReceiveCommand command : commands) {
      try {
        if (command.getResult() != ReceiveCommand.Result.OK
            || command.getType() == ReceiveCommand.Type.DELETE
            || !command.getRefName().startsWith(Constants.R_HEADS)) {
          continue;
        }
        String branch = command.getRefName().substring(Constants.R_HEADS.length());
        String body =
            eventBody(repoId, branch, command.getOldId().name(), command.getNewId().name());
        if (!ciSuppressed) {
          postToCi(repoId, branch, body);
        }
        postToProjects(repoId, branch, body);
      } catch (Exception e) {
        LOG.debugf("post-receive event for %s skipped: %s", repoId, e.toString());
      }
    }
  }

  /** The one body both consumers read, serialised once. */
  private String eventBody(String repoId, String branch, String oldSha, String newSha)
      throws Exception {
    return objectMapper.writeValueAsString(
        Map.of("repoId", repoId, "branch", branch, "oldSha", oldSha, "newSha", newSha));
  }

  private void postToCi(String repoId, String branch, String body) {
    HttpRequest.Builder request = request(ciIntakeUrl, body);
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
              send(request.build(), "CI", repoId, branch);
            },
            failure -> {
              // A token we could not get is not a reason to drop the event. qits-ci with its gate
              // off takes it either way, and with the gate on it refuses it — which is the right
              // answer for a caller that cannot prove who it is.
              LOG.debugf("CI token for %s@%s unavailable: %s", repoId, branch, failure);
              send(request.build(), "CI", repoId, branch);
            });
  }

  /** The backup trigger. No credential and no token fetch, so it goes straight out. */
  private void postToProjects(String repoId, String branch, String body) {
    send(request(projectsIntakeUrl, body).build(), "projects", repoId, branch);
  }

  private HttpRequest.Builder request(String intakeUrl, String body) {
    return HttpRequest.newBuilder(URI.create(intakeUrl))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  /** The bearer for qits-ci, or empty when this deployment has no client credentials. */
  private Uni<Optional<String>> bearer() {
    if (!machineTokenEnabled) {
      return Uni.createFrom().item(Optional.empty());
    }
    return oidcClient.getTokens().map(tokens -> Optional.of(tokens.getAccessToken()));
  }

  private void send(HttpRequest request, String consumer, String repoId, String branch) {
    client
        .sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, failure) -> {
              if (failure != null) {
                LOG.debugf("%s event for %s@%s failed: %s", consumer, repoId, branch, failure);
              } else if (response.statusCode() >= 400) {
                LOG.debugf(
                    "%s event for %s@%s rejected: %d",
                    consumer, repoId, branch, response.statusCode());
              }
            });
  }
}
