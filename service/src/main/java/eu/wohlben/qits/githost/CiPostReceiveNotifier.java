package eu.wohlben.qits.githost;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@ApplicationScoped
public class CiPostReceiveNotifier {

  private static final Logger LOG = Logger.getLogger(CiPostReceiveNotifier.class);
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.ci.intake-url")
  String intakeUrl;

  // The same static secret the intake's CiTokenFilter checks — blank (the dev/test default) sends
  // no header, matching the filter's open mode.
  @ConfigProperty(name = "qits.ci.token")
  Optional<String> token;

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
    CLIENT
        .sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
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
